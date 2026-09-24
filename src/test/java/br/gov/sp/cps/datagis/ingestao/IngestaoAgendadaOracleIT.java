package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Oracle local real (mesmo padrão do ConjuntoOracleIT). Teste de ponta a ponta
 * do
 * agendamento: AgendadorInicializacaoRunner registra o trigger no Quartz (JDBC
 * store)
 * -> Quartz dispara IngestaoAgendadaJob -> IngestaoExecucaoService ->
 * IngestaoCsvExecutor -> gravação em BRONZE.registro_bruto -> esteira de
 * validação
 * promove pra SILVER.foco_calor.
 *
 * <p>
 * Só a resposta HTTP é falsa (HttpFetchClient mockado). Não usa @Transactional
 * com rollback porque o job roda numa thread do Quartz, em outra transação -
 * por
 * isso o @AfterEach apaga tudo o que o teste criou (Silver, Bronze, lote e
 * fonte).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.batch.job.enabled=false",
        // Scheduler com nome próprio: no Quartz o SCHED_NAME faz parte da chave de
        // todas
        // as tabelas QRTZ_*, então o backend rodando no docker compose nunca enxerga os
        // triggers deste teste, e este teste nunca mexe nos triggers reais.
        "spring.quartz.scheduler-name=datagisTesteIT"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
class IngestaoAgendadaOracleIT {

    private static final String GRUPO = "ingestao";
    private static final String CRON_TESTE = "0 0 3 1 1 ?"; // 1º de janeiro, nunca dispara sozinho no teste
    private static final String URL_FALSA = "http://fonte-falsa.local/focos.csv";
    private static final long TEMPO_MAXIMO_MS = 30_000;

    private static final String CSV_FALSO = """
            latitude,longitude,municipio,data_hora
            -25.4284,-49.2733,Curitiba,2026-09-01 10:00:00
            -23.3045,-51.1696,Londrina,2026-09-01 11:30:00
            -25.0916,-50.1668,Ponta Grossa,2026-09-01 14:15:00
            """;

    @Autowired
    private FonteDadoRepository fonteDadoRepository;

    @Autowired
    private AgendadorInicializacaoRunner runner;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private HttpFetchClient httpFetchClient;

    private Long fonteId;

    @AfterEach
    void limparTudoQueOTesteCriou() throws Exception {
        // Apaga só os jobs/triggers do scheduler "datagisTesteIT" - os do backend ficam
        // intactos.
        scheduler.clear();
        if (fonteId != null) {
            // Ordem: da camada mais "nova" pra mais "antiga" (Silver -> Bronze -> lote ->
            // fonte).
            jdbc.update("DELETE FROM SILVER.foco_calor WHERE fonte_dado_id = ?", fonteId);
            jdbc.update("DELETE FROM BRONZE.registro_bruto WHERE fonte_dado_id = ?", fonteId);
            jdbc.update("DELETE FROM CONTROLE.lote_ingestao WHERE fonte_dado_id = ?", fonteId);
            jdbc.update("DELETE FROM CONTROLE.fonte_dado WHERE id = ?", fonteId);
        }
    }

    @Test
    void agendamentoDisparaIngestaoEGravaRegistrosNoBronzeENaSilver() throws Exception {
        // Só a URL da fonte de teste responde; qualquer outra devolve null e falha.
        when(httpFetchClient.buscarCorpoBruto(URL_FALSA)).thenReturn(CSV_FALSO);

        FonteDado fonte = fonteDadoRepository.save(FonteDado.builder()
                .nome("TESTE IT - Focos de calor (CSV falso)")
                .orgao("Teste")
                .tipoAquisicao("DOWNLOAD_URL")
                .urlOrigem(URL_FALSA)
                .cronExpressao(CRON_TESTE)
                .formatoEsperado("CSV")
                .campoGeometriaLat("latitude")
                .campoGeometriaLon("longitude")
                .dataAdd(LocalDateTime.now())
                .build());
        fonteId = fonte.getId();

        JobKey jobKey = JobKey.jobKey("ingestao-fonte-" + fonteId, GRUPO);
        TriggerKey triggerKey = TriggerKey.triggerKey("trigger-fonte-" + fonteId, GRUPO);

        // 1) O runner já rodou na subida, antes da fonte existir; roda de novo pra
        // pegá-la.
        runner.run(null);

        // Na subida o runner também agendou as fontes reais neste scheduler de teste.
        // Remove esses triggers pra nenhuma fonte real rodar com o HTTP falso.
        for (TriggerKey chave : scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(GRUPO))) {
            if (!chave.equals(triggerKey)) {
                scheduler.unscheduleJob(chave);
            }
        }

        Trigger trigger = scheduler.getTrigger(triggerKey);
        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getCronExpression()).isEqualTo(CRON_TESTE);

        // 2) Dispara agora, pelo próprio Quartz (mesmo caminho do cron, sem esperar o
        // horário).
        scheduler.triggerJob(jobKey);

        // 3) O job roda numa thread do Quartz; espera o service registrar o resultado.
        FonteDado depois = aguardarFimDaExecucao(fonteId);

        assertThat(depois.getUltimoStatusExecucao())
                .as("mensagem de erro gravada: %s", depois.getUltimoErroMensagem())
                .isEqualTo("SUCESSO");

        // 4) Bronze: 1 lote, 3 registros, todos com geometria.
        assertThat(contar("SELECT COUNT(*) FROM CONTROLE.lote_ingestao WHERE fonte_dado_id = ?"))
                .isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM BRONZE.registro_bruto WHERE fonte_dado_id = ?"))
                .isEqualTo(3);
        assertThat(contar(
                "SELECT COUNT(*) FROM BRONZE.registro_bruto WHERE fonte_dado_id = ? AND geometria_wkt IS NOT NULL"))
                .isEqualTo(3);

        // 5) Silver: a esteira de validação promove os 3 focos pra SILVER.foco_calor.
        assertThat(aguardarContagem("SELECT COUNT(*) FROM SILVER.foco_calor WHERE fonte_dado_id = ?", 3))
                .isEqualTo(3);
    }

    private int contar(String sql) {
        return jdbc.queryForObject(sql, Integer.class, fonteId);
    }

    /**
     * Repete a contagem até bater o esperado ou estourar o tempo; devolve a última
     * contagem.
     */
    private int aguardarContagem(String sql, int esperado) throws InterruptedException {
        long limite = System.currentTimeMillis() + TEMPO_MAXIMO_MS;
        int atual = contar(sql);
        while (atual != esperado && System.currentTimeMillis() < limite) {
            Thread.sleep(250);
            atual = contar(sql);
        }
        return atual;
    }

    private FonteDado aguardarFimDaExecucao(Long id) throws InterruptedException {
        long limite = System.currentTimeMillis() + TEMPO_MAXIMO_MS;
        while (System.currentTimeMillis() < limite) {
            FonteDado atual = fonteDadoRepository.findById(id).orElseThrow();
            if (atual.getUltimoStatusExecucao() != null) {
                return atual;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(
                "Ingestão não terminou em 30s - o Quartz pode nem ter disparado o job (confira o log)");
    }
}