package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Equivalência sequencial × paralelo contra o Oracle real (docs/publicacao-gold-paralela.md
 * §6.2) - sem mock pra PL/SQL, mesmo princípio dos outros {@code *OracleIT}. Usa imóveis
 * já reais na Silver (não insere massa de teste - o circuito de homologação já está lá) e
 * se apoia na idempotência do {@code hash_versao} (pkg_gold_publicacao.registrar_resultado,
 * CLAUDE.md §2.6): recalcular um imóvel sem o dado-fonte mudar devolve o mesmo
 * {@code indicador_versao_id} em vez de duplicar linha. Por isso rodar o MESMO lote de
 * imóveis duas vezes - primeiro com {@code GoldPublicacaoService} configurado com
 * paralelismo 1, depois com paralelismo 4 - tem que gravar exatamente os mesmos 7
 * valores, nenhuma versão nova, nenhum imóvel faltando.
 *
 * <p>Escopo pequeno de propósito ({@value #TAMANHO_AMOSTRA} imóveis, não o circuito
 * inteiro) - o alvo aqui é provar a equivalência de resultado sob concorrência de
 * verdade (conexões reais do pool, procedures reais), não medir velocidade (isso é o
 * servidor, §6.3 do doc, fora do escopo de um teste automatizado).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
class PublicacaoGoldParaleloOracleIT {

    private static final int TAMANHO_AMOSTRA = 40;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SimplificadorGeometriaMapa simplificador;

    @Test
    void recalcularEmSequencialEEmParaleloDevolveOMesmoResultado() {
        List<String> amostra = jdbcTemplate.queryForList(
                "SELECT cod_imovel FROM silver.imovel_rural WHERE ROWNUM <= ?",
                String.class, TAMANHO_AMOSTRA);
        assertThat(amostra).as("precisa de imóveis reais na Silver pra este teste fazer sentido")
                .hasSize(TAMANHO_AMOSTRA);

        GoldPublicacaoService sequencial = new GoldPublicacaoService(jdbcTemplate, simplificador, 1);
        for (String cod : amostra) {
            sequencial.recalcularImovel(cod);
        }
        Map<String, Map<String, Object>> resultadoSequencial = capturarIndicadores(amostra);

        GoldPublicacaoService paralelo = new GoldPublicacaoService(jdbcTemplate, simplificador, 4);
        for (String cod : amostra) {
            paralelo.recalcularImovel(cod);
        }
        Map<String, Map<String, Object>> resultadoParalelo = capturarIndicadores(amostra);

        assertThat(resultadoParalelo.keySet()).as("nenhum imóvel pode faltar nem duplicar")
                .containsExactlyInAnyOrderElementsOf(resultadoSequencial.keySet());

        for (String cod : amostra) {
            Map<String, Object> antes = resultadoSequencial.get(cod);
            Map<String, Object> depois = resultadoParalelo.get(cod);
            assertThat(depois.get("INDICADOR_VERSAO_ID"))
                    .as("recalcular sem mudança de dado-fonte tem que reusar a mesma versão (hash_versao), imóvel " + cod)
                    .isEqualTo(antes.get("INDICADOR_VERSAO_ID"));
            for (String coluna : List.of(
                    "VALOR_ISAP", "VALOR_IAE", "VALOR_IDESMAT", "VALOR_IFC",
                    "VALOR_ICV", "VALOR_IRL", "VALOR_IAPP")) {
                assertThat(depois.get(coluna))
                        .as(coluna + " do imóvel " + cod + " tem que bater entre sequencial e paralelo")
                        .isEqualTo(antes.get(coluna));
            }
        }
    }

    private Map<String, Map<String, Object>> capturarIndicadores(List<String> codigos) {
        String placeholders = String.join(",", codigos.stream().map(c -> "?").toList());
        List<Map<String, Object>> linhas = jdbcTemplate.queryForList(
                """
                SELECT cod_imovel, indicador_versao_id, valor_isap, valor_iae, valor_idesmat,
                       valor_ifc, valor_icv, valor_irl, valor_iapp
                  FROM (SELECT ip.*, ROW_NUMBER() OVER (
                               PARTITION BY cod_imovel ORDER BY data_publicacao DESC, id DESC) ordem
                          FROM gold.imovel_publicado ip
                         WHERE cod_imovel IN (%s))
                 WHERE ordem = 1
                """.formatted(placeholders),
                codigos.toArray());
        return linhas.stream().collect(java.util.stream.Collectors.toMap(
                l -> (String) l.get("COD_IMOVEL"), l -> l));
    }
}
