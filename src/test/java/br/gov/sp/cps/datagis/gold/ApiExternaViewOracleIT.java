package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * View da API externa (V091, LIZARDS-57), contra o Oracle local real. Cada teste grava
 * os proprios imoveis de teste e faz rollback - nada fica no banco.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class ApiExternaViewOracleIT {

    private static final String IMOVEL = "PR-IT-LIZARDS57-0001";
    private static final String OUTRO_IMOVEL = "PR-IT-LIZARDS57-0002";

    @Autowired private JdbcTemplate jdbc;

    private int imoveisDeFaixa;

    /**
     * A publicacao mais recente do imovel vale, e dentro dela o resultado mais recente ate
     * o momento da publicacao - o mesmo que pkg_gold_publicacao.f_ultimo_valor gravou nas
     * colunas valor_* (e o que o portal mostra).
     */
    @Test
    void mostraSoOResultadoDaPublicacaoMaisRecente() {
        long v1 = resultado(IMOVEL, "ISAP", 10.0, "2026-11-01 10:00:00");
        publicar(IMOVEL, v1, "2026-11-01 10:05:00", 10.0);
        long v2 = resultado(IMOVEL, "ISAP", 20.0, "2026-11-02 10:00:00");
        publicar(IMOVEL, v2, "2026-11-02 10:05:00", 20.0);
        // Recalculo depois da ultima publicacao: ainda nao publicado, nao pode sair na API.
        resultado(IMOVEL, "ISAP", 99.0, "2026-11-03 10:00:00");

        List<Map<String, Object>> linhas = vigentes(IMOVEL);

        assertThat(linhas).hasSize(1);
        assertThat(numero(linhas.get(0), "VALOR")).isEqualByComparingTo("20");
        assertThat(numero(linhas.get(0), "INDICADOR_VERSAO_ID")).isEqualByComparingTo(String.valueOf(v2));
    }

    /** Uma linha por indicador calculado; indicador sem resultado nao gera linha. */
    @Test
    void geraUmaLinhaPorIndicadorComResultado() {
        long isap = resultado(IMOVEL, "ISAP", 5.0, "2026-11-01 10:00:00");
        resultado(IMOVEL, "IFC", 0.0, "2026-11-01 10:00:01");
        publicar(IMOVEL, isap, "2026-11-01 10:05:00", 5.0);

        List<Object> indicadores = vigentes(IMOVEL).stream().map(l -> l.get("INDICADOR")).toList();

        assertThat(indicadores).containsExactlyInAnyOrder("ISAP", "IFC");
    }

    /** Imovel calculado mas nunca publicado nao existe para a API. */
    @Test
    void imovelNaoPublicadoNaoAparece() {
        resultado(IMOVEL, "ISAP", 5.0, "2026-11-01 10:00:00");

        assertThat(vigentes(IMOVEL)).isEmpty();
    }

    /** MENOR_MELHOR: ate limite_bom e BOM, ate limite_atencao e ATENCAO, acima e CRITICO. */
    @Test
    void faixaDeIndicadorMenorMelhor() {
        limites("ISAP", 30, 60);

        assertThat(faixa("ISAP", 30.0)).isEqualTo("BOM");
        assertThat(faixa("ISAP", 60.0)).isEqualTo("ATENCAO");
        assertThat(faixa("ISAP", 60.01)).isEqualTo("CRITICO");
    }

    /** MAIOR_MELHOR: a partir de limite_bom e BOM, a partir de limite_atencao e ATENCAO. */
    @Test
    void faixaDeIndicadorMaiorMelhor() {
        limites("ICV", 70, 40);

        assertThat(faixa("ICV", 70.0)).isEqualTo("BOM");
        assertThat(faixa("ICV", 40.0)).isEqualTo("ATENCAO");
        assertThat(faixa("ICV", 39.99)).isEqualTo("CRITICO");
    }

    @Test
    void semValorNaoTemFaixa() {
        assertThat(faixa("ISAP", null)).isNull();
    }

    /** Indicador desativado no catalogo some da API. */
    @Test
    void indicadorInativoNaoAparece() {
        long isap = resultado(IMOVEL, "ISAP", 5.0, "2026-11-01 10:00:00");
        publicar(IMOVEL, isap, "2026-11-01 10:05:00", 5.0);
        jdbc.update("UPDATE gold.tipo_indicador SET ativo = 0 WHERE codigo = 'ISAP'");

        assertThat(vigentes(IMOVEL)).isEmpty();
    }

    /**
     * Com dado real publicado no banco: o valor de cada linha da API e igual ao da coluna
     * valor_* que o portal le, e cada imovel aparece uma vez por indicador. Sem nada
     * publicado, so confere que a view responde.
     */
    @Test
    void comDadoRealOValorBateComOQueOPortalMostra() {
        Integer divergentes = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM gold.vw_api_indicador_vigente v
                  JOIN (SELECT p.*, ROW_NUMBER() OVER (PARTITION BY p.cod_imovel
                                         ORDER BY p.data_publicacao DESC, p.id DESC) AS ordem
                          FROM gold.imovel_publicado p) i
                    ON i.cod_imovel = v.cod_imovel AND i.ordem = 1
                 WHERE DECODE(v.valor, CASE v.indicador
                           WHEN 'ICV' THEN i.valor_icv   WHEN 'IRL' THEN i.valor_irl
                           WHEN 'IAPP' THEN i.valor_iapp WHEN 'ISAP' THEN i.valor_isap
                           WHEN 'IAE' THEN i.valor_iae   WHEN 'IDESMAT' THEN i.valor_idesmat
                           WHEN 'IFC' THEN i.valor_ifc END, 0, 1) = 1
                """, Integer.class);
        Integer repetidos = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT cod_imovel, indicador FROM gold.vw_api_indicador_vigente
                                       GROUP BY cod_imovel, indicador HAVING COUNT(*) > 1)
                """, Integer.class);

        assertThat(divergentes).isZero();
        assertThat(repetidos).isZero();
    }

    /** Grava um resultado de um indicador para o imovel, como o registrar_resultado faz. */
    private long resultado(String codImovel, String indicador, Double valor, String quando) {
        long regraId = jdbc.queryForObject("""
                SELECT MAX(rc.id) FROM gold.regra_calculo rc
                  JOIN gold.tipo_indicador t ON t.id = rc.tipo_indicador_id
                 WHERE t.codigo = ?
                """, Long.class, indicador);
        String hash = "it-lizards57-" + codImovel + "-" + indicador + "-" + quando;
        jdbc.update("""
                INSERT INTO gold.indicador_versao (cod_imovel, regra_calculo_id, data_processamento, hash_versao)
                VALUES (?, ?, TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS'), ?)
                """, codImovel, regraId, quando, hash);
        long versaoId = jdbc.queryForObject(
                "SELECT id FROM gold.indicador_versao WHERE hash_versao = ?", Long.class, hash);
        jdbc.update("""
                INSERT INTO gold.indicador_resultado (indicador_versao_id, tipo_indicador_id, valor)
                SELECT ?, id, ? FROM gold.tipo_indicador WHERE codigo = ?
                """, versaoId, valor, indicador);
        return versaoId;
    }

    private void publicar(String codImovel, long versaoId, String quando, Double valorIsap) {
        jdbc.update("""
                INSERT INTO gold.imovel_publicado
                    (cod_imovel, indicador_versao_id, municipio_cod_ibge, area_ha, valor_isap, data_publicacao)
                VALUES (?, ?, 4115200, 10, ?, TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS'))
                """, codImovel, versaoId, valorIsap, quando);
    }

    private void limites(String indicador, int bom, int atencao) {
        jdbc.update("UPDATE gold.tipo_indicador SET limite_bom = ?, limite_atencao = ?, ativo = 1 WHERE codigo = ?",
                bom, atencao, indicador);
    }

    /** Publica um imovel novo so com aquele valor e devolve a faixa que a view calculou. */
    private String faixa(String indicador, Double valor) {
        String codImovel = OUTRO_IMOVEL + "-" + (++imoveisDeFaixa);
        long versaoId = resultado(codImovel, indicador, valor, "2026-11-01 10:00:00");
        publicar(codImovel, versaoId, "2026-11-01 10:05:00", null);
        return jdbc.queryForObject(
                "SELECT faixa FROM gold.vw_api_indicador_vigente WHERE cod_imovel = ? AND indicador = ?",
                String.class, codImovel, indicador);
    }

    private List<Map<String, Object>> vigentes(String codImovel) {
        return jdbc.queryForList("SELECT * FROM gold.vw_api_indicador_vigente WHERE cod_imovel = ?", codImovel);
    }

    private static BigDecimal numero(Map<String, Object> linha, String coluna) {
        return new BigDecimal(linha.get(coluna).toString());
    }
}
