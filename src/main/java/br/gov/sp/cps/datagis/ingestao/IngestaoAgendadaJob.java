package br.gov.sp.cps.datagis.ingestao;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Job do Quartz que dispara a ingestão de uma fonte específica. O Quartz cria
 * uma
 * instância nova a cada disparo; o {@code fonteId} chega via
 * {@link org.quartz.JobDataMap}
 * (configurado no Trigger/JobDetail, criado num commit mais à frente). Daqui em
 * diante,
 * delega tudo pro {@link IngestaoExecucaoService}, que já sabe achar o executor
 * certo
 * pra essa fonte.
 *
 * <p>Uma execução por fonte de cada vez (LIZARDS-54): {@code @DisallowConcurrentExecution}
 * segura o disparo seguinte enquanto o anterior roda (o job é um por fonte), e a checagem
 * do {@link ProgressoExecucaoRegistry} pula o disparo quando a mesma fonte já está rodando
 * por outro caminho (botão da tela). Sem isso, as duas execuções gravavam lotes em dobro.
 */
@DisallowConcurrentExecution
public class IngestaoAgendadaJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(IngestaoAgendadaJob.class);
    private static final String CHAVE_FONTE_ID = "fonteId";

    @Autowired
    private IngestaoExecucaoService ingestaoExecucaoService;

    @Autowired
    private ProgressoExecucaoRegistry progressoRegistry;

    @Autowired
    private AgendamentoFonteService agendamentoFonteService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long fonteId = context.getMergedJobDataMap().getLong(CHAVE_FONTE_ID);
        try {
            if (progressoRegistry.isExecutando(fonteId)) {
                log.warn("Execucao agendada da fonte {} pulada: ja existe uma execucao em andamento", fonteId);
                return;
            }
            ingestaoExecucaoService.executar(fonteId);
        } catch (Exception e) {
            throw new JobExecutionException(
                    "Falha ao executar ingestao agendada da fonte " + fonteId, e);
        } finally {
            // Depois da execução, não antes: ela grava a fonte inteira e sobrescreveria a data.
            // Lê o trigger de agora (pode ter sido desligado ou editado durante a execução) e
            // nunca deixa uma falha aqui esconder o erro da ingestão, que sai do catch acima.
            try {
                agendamentoFonteService.atualizarProximaExecucaoDoTrigger(fonteId);
            } catch (Exception e) {
                log.warn("Nao foi possivel gravar a proxima execucao da fonte {}", fonteId, e);
            }
        }
    }
}
