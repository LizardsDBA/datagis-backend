package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Views do mapa de cobertura (V080) e índice espacial da Gold (V081), contra o Oracle
 * local real. Cada teste faz rollback - a camada de teste nunca fica gravada.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class MapaCoberturaViewsOracleIT {

    private static final String CAMADA = "TESTE_IT_LIZARDS35";

    @Autowired private JdbcTemplate jdbc;

    /** A versão válida é a última CONCLUIDA - nunca a última gravada (V067). */
    @Test
    void viewMostraSoAVersaoConcluidaMaisRecente() {
        long camadaId = criarCamada();
        publicar(camadaId, 1, "CONCLUIDA", "versao-1");
        publicar(camadaId, 2, "CONCLUIDA", "versao-2");
        publicar(camadaId, 3, "FALHOU", "versao-3");
        publicar(camadaId, 4, "EM_ANDAMENTO", "versao-4");

        List<String> vigentes = jdbc.queryForList(
                "SELECT cod_natural FROM gold.vw_mapa_feature_vigente WHERE camada = ?", String.class, CAMADA);

        assertThat(vigentes).containsExactly("versao-2");
    }

    @Test
    void camadaSemPublicacaoConcluidaNaoMostraNada() {
        long camadaId = criarCamada();
        publicar(camadaId, 1, "FALHOU", "versao-1");

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gold.vw_mapa_feature_vigente WHERE camada = ?", Integer.class, CAMADA);

        assertThat(total).isZero();
    }

    /** O GeoServer manda o INTERSECTS como SDO_RELATE; pela view ele precisa chegar no índice. */
    @Test
    void interseccaoPelaViewUsaOIndiceEspacial() {
        jdbc.execute("""
                EXPLAIN PLAN SET STATEMENT_ID = 'lizards35_it' FOR
                SELECT i.id FROM gold.vw_mapa_imovel i, gold.vw_mapa_municipio m
                 WHERE m.cod_natural = '4119905'
                   AND SDO_RELATE(i.geometria, m.geometria, 'mask=anyinteract') = 'TRUE'
                """);

        List<String> plano = jdbc.queryForList(
                "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, 'lizards35_it', 'BASIC'))",
                String.class);

        assertThat(String.join("\n", plano))
                .contains("DOMAIN INDEX")
                .contains("IDX_FEATURE_PUBLICADA_GEOM");
    }

    /** Sem o metadado espacial o GeoServer não reconhece a coluna de geometria da view. */
    @Test
    void todasAsViewsTemMetadadoEspacial() {
        List<String> registradas = jdbc.queryForList(
                "SELECT table_name FROM all_sdo_geom_metadata WHERE owner = 'GOLD' AND table_name LIKE 'VW\\_MAPA\\_%' ESCAPE '\\'",
                String.class);

        assertThat(registradas).containsExactlyInAnyOrder(
                "VW_MAPA_LIMITE_PR", "VW_MAPA_MUNICIPIO", "VW_MAPA_IMOVEL", "VW_MAPA_AREA_PROTEGIDA",
                "VW_MAPA_EMBARGO", "VW_MAPA_DESMATAMENTO", "VW_MAPA_FOCO_CALOR");
    }

    private long criarCamada() {
        jdbc.update("""
                INSERT INTO gold.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo)
                VALUES (?, 'Camada de teste', 'TEMATICA', 'PONTO')
                """, CAMADA);
        return jdbc.queryForObject("SELECT id FROM gold.mapa_camada WHERE codigo = ?", Long.class, CAMADA);
    }

    private void publicar(long camadaId, int versao, String status, String codNatural) {
        jdbc.update("""
                INSERT INTO gold.publicacao_camada_mapa (mapa_camada_id, versao_publicacao, status)
                VALUES (?, ?, ?)
                """, camadaId, versao, status);
        jdbc.update("""
                INSERT INTO gold.feature_publicada (mapa_camada_id, cod_natural, geometria, versao_publicacao)
                VALUES (?, ?, SDO_GEOMETRY('POINT (-50 -25)', 4674), ?)
                """, camadaId, codNatural, versao);
    }
}
