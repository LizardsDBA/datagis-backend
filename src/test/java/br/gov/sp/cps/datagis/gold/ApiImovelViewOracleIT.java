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
 * View dos imoveis da API externa (V092, LIZARDS-57), contra o Oracle local real. Cada
 * teste grava os proprios imoveis e versoes da camada de municipios e faz rollback - nada
 * fica no banco.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class ApiImovelViewOracleIT {

    private static final String IMOVEL = "PR-IT-LIZARDS57-0001";
    /** Codigo IBGE que nao existe no Parana, para nao colidir com municipio real. */
    private static final long MUNICIPIO_TESTE = 4199999L;

    @Autowired private JdbcTemplate jdbc;

    /** Imovel republicado sai uma vez so, com os valores da publicacao mais recente. */
    @Test
    void imovelRepublicadoSaiUmaVezComAPublicacaoMaisRecente() {
        publicarImovel(IMOVEL, "2026-11-01 10:00:00", 10.0);
        publicarImovel(IMOVEL, "2026-11-02 10:00:00", 20.0);

        List<Map<String, Object>> linhas = imovel(IMOVEL);

        assertThat(linhas).hasSize(1);
        assertThat(numero(linhas.get(0), "VALOR_ISAP")).isEqualByComparingTo("20");
    }

    /**
     * A camada MUNICIPIO guarda uma copia por publicacao: o imovel continua saindo uma vez,
     * com o nome da ultima publicacao CONCLUIDA - nem a antiga, nem a que falhou.
     */
    @Test
    void municipioVemDaUltimaPublicacaoConcluidaDaCamadaSemRepetirOImovel() {
        long versao = proximaVersaoMunicipio();
        publicarMunicipio(versao, "CONCLUIDA", "Nome antigo");
        publicarMunicipio(versao + 1, "CONCLUIDA", "Nome atual");
        publicarMunicipio(versao + 2, "FALHOU", "Nome da publicacao que falhou");
        publicarImovel(IMOVEL, "2026-11-01 10:00:00", 5.0);

        List<Map<String, Object>> linhas = imovel(IMOVEL);

        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0).get("MUNICIPIO")).isEqualTo("Nome atual");
    }

    /** Municipio fora da camada publicada nao derruba o imovel: sai, so sem o nome. */
    @Test
    void imovelComMunicipioForaDaCamadaSaiSemONome() {
        publicarImovel(IMOVEL, "2026-11-01 10:00:00", 5.0);

        List<Map<String, Object>> linhas = imovel(IMOVEL);

        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0).get("MUNICIPIO")).isNull();
        assertThat(numero(linhas.get(0), "MUNICIPIO_COD_IBGE")).isEqualByComparingTo(String.valueOf(MUNICIPIO_TESTE));
    }

    /** As colunas que a API vai ler, na ordem combinada com o time. */
    @Test
    void colunasDaView() {
        List<String> colunas = jdbc.queryForList("""
                SELECT column_name FROM all_tab_columns
                 WHERE owner = 'GOLD' AND table_name = 'VW_API_IMOVEL'
                 ORDER BY column_id
                """, String.class);

        assertThat(colunas).containsExactly(
                "COD_IMOVEL", "MUNICIPIO_COD_IBGE", "MUNICIPIO", "AREA_HA", "GEOMETRIA",
                "VALOR_ICV", "VALOR_IRL", "VALOR_IRL_DEFICIT_HA", "VALOR_IAPP", "VALOR_IAPP_PASSIVO_HA",
                "VALOR_ISAP", "VALOR_IAE", "VALOR_IDESMAT", "VALOR_IFC", "DATA_PUBLICACAO");
    }

    /**
     * Com dado real publicado no banco: um imovel por linha e o mesmo total de imoveis
     * publicados. Sem nada publicado, so confere que a view responde.
     */
    @Test
    void comDadoRealSaiUmaLinhaPorImovelPublicado() {
        Integer linhas = jdbc.queryForObject("SELECT COUNT(*) FROM gold.vw_api_imovel", Integer.class);
        Integer imoveisDistintos = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT cod_imovel) FROM gold.vw_api_imovel", Integer.class);
        Integer publicados = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT cod_imovel) FROM gold.imovel_publicado", Integer.class);

        assertThat(linhas).isEqualTo(imoveisDistintos).isEqualTo(publicados);
    }

    /** Publica o imovel como o pkg_gold_publicacao.publicar_imovel faz (com a versao que ele exige). */
    private void publicarImovel(String codImovel, String quando, Double valorIsap) {
        long regraId = jdbc.queryForObject("""
                SELECT MAX(rc.id) FROM gold.regra_calculo rc
                  JOIN gold.tipo_indicador t ON t.id = rc.tipo_indicador_id
                 WHERE t.codigo = 'ISAP'
                """, Long.class);
        String hash = "it-lizards57-" + codImovel + "-" + quando;
        jdbc.update("""
                INSERT INTO gold.indicador_versao (cod_imovel, regra_calculo_id, data_processamento, hash_versao)
                VALUES (?, ?, TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS'), ?)
                """, codImovel, regraId, quando, hash);
        long versaoId = jdbc.queryForObject(
                "SELECT id FROM gold.indicador_versao WHERE hash_versao = ?", Long.class, hash);
        jdbc.update("""
                INSERT INTO gold.imovel_publicado
                    (cod_imovel, indicador_versao_id, municipio_cod_ibge, area_ha, valor_isap, data_publicacao)
                VALUES (?, ?, ?, 10, ?, TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS'))
                """, codImovel, versaoId, MUNICIPIO_TESTE, valorIsap, quando);
    }

    /** Proxima versao livre da camada MUNICIPIO (mesma conta do GoldPublicacaoService). */
    private long proximaVersaoMunicipio() {
        return jdbc.queryForObject("""
                SELECT GREATEST(
                    (SELECT COALESCE(MAX(versao_publicacao), 0) FROM gold.publicacao_camada_mapa WHERE mapa_camada_id = m.id),
                    (SELECT COALESCE(MAX(versao_publicacao), 0) FROM gold.feature_publicada WHERE mapa_camada_id = m.id)) + 1
                  FROM gold.mapa_camada m WHERE m.codigo = 'MUNICIPIO'
                """, Long.class);
    }

    private void publicarMunicipio(long versao, String status, String nome) {
        long camadaId = jdbc.queryForObject(
                "SELECT id FROM gold.mapa_camada WHERE codigo = 'MUNICIPIO'", Long.class);
        jdbc.update("""
                INSERT INTO gold.publicacao_camada_mapa (mapa_camada_id, versao_publicacao, status)
                VALUES (?, ?, ?)
                """, camadaId, versao, status);
        jdbc.update("""
                INSERT INTO gold.feature_publicada
                    (mapa_camada_id, cod_natural, rotulo, municipio_cod_ibge, geometria, versao_publicacao)
                VALUES (?, ?, ?, ?, SDO_GEOMETRY('POINT (-51 -24)', 4674), ?)
                """, camadaId, String.valueOf(MUNICIPIO_TESTE), nome, MUNICIPIO_TESTE, versao);
    }

    private List<Map<String, Object>> imovel(String codImovel) {
        return jdbc.queryForList("SELECT * FROM gold.vw_api_imovel WHERE cod_imovel = ?", codImovel);
    }

    private static BigDecimal numero(Map<String, Object> linha, String coluna) {
        return new BigDecimal(linha.get(coluna).toString());
    }
}
