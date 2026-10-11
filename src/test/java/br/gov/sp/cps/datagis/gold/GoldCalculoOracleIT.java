package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Oracle local real — testa as 7 procedures PL/SQL Silver->Gold
 * (docs/gold-procedures-calculo-publicacao.md). Sem mock pra PL/SQL (§3.4 do doc) -
 * popula um imóvel + restrições sintéticas com geometria conhecida (retângulos,
 * sobreposição exata e previsível), roda a transição real via
 * {@link GoldPublicacaoService} (mesmo caminho que o endpoint HTTP usa) e confere o
 * resultado. Cada teste usa rollback automático (@Transactional); não executa
 * migrations nem ingestões.
 *
 * <p>Imóvel de teste: retângulo lon [-51.00,-50.99] x lat [-24.00,-23.99] (~113 ha).
 * Área protegida sintética cobre a metade OESTE (ISAP esperado ~50%). Embargo cobre o
 * quarto LESTE (IAE esperado ~25%). Desmatamento cobre o imóvel inteiro (IDesmat
 * esperado ~100%). 2 focos de calor: 1 dentro, 1 bem fora (IFC = 1 / area_ha * 1000).
 * Reserva Legal cobre o quarto NORTE (IRL esperado ~25% - LIZARDS-32, V072). APP
 * declarada cobre a metade SUL, vegetação nativa declarada cobre a metade OESTE - a
 * interseção dos dois é o quarto SUDOESTE, metade da área da APP (IAPP esperado ~50%
 * - única dos 3 novos que precisa de interseção espacial). Vegetação nativa também
 * alimenta o ICV (razão direta de área, mesma metade OESTE, ICV esperado ~50% -
 * LIZARDS-31, V073).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class GoldCalculoOracleIT {

    private static final String COD_IMOVEL = "TESTE-GOLD-OracleIT-0001";
    private static final String WKT_IMOVEL = "POLYGON((-51.00 -24.00, -50.99 -24.00, -50.99 -23.99, -51.00 -23.99, -51.00 -24.00))";
    private static final String WKT_AREA_PROTEGIDA_OESTE = "POLYGON((-51.00 -24.00, -50.995 -24.00, -50.995 -23.99, -51.00 -23.99, -51.00 -24.00))";
    private static final String WKT_EMBARGO_LESTE = "POLYGON((-50.9925 -24.00, -50.99 -24.00, -50.99 -23.99, -50.9925 -23.99, -50.9925 -24.00))";
    private static final String WKT_DESMATAMENTO_TOTAL = "POLYGON((-51.001 -24.001, -50.989 -24.001, -50.989 -23.989, -51.001 -23.989, -51.001 -24.001))";
    private static final String WKT_RESERVA_LEGAL_NORTE = "POLYGON((-51.00 -23.9925, -50.99 -23.9925, -50.99 -23.99, -51.00 -23.99, -51.00 -23.9925))";
    private static final String WKT_APP_DECLARADA_SUL = "POLYGON((-51.00 -24.00, -50.99 -24.00, -50.99 -23.995, -51.00 -23.995, -51.00 -24.00))";
    private static final String WKT_VEGETACAO_NATIVA_OESTE = "POLYGON((-51.00 -24.00, -50.995 -24.00, -50.995 -23.99, -51.00 -23.99, -51.00 -24.00))";

    @Autowired private GoldPublicacaoService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        jdbc.update("""
                INSERT INTO silver.imovel_rural
                    (cod_imovel, area_ha, geometria, atributos_origem, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, SDO_GEOM.SDO_AREA(SDO_GEOMETRY(?, 4674), 0.05, 'unit=Hectare'), SDO_GEOMETRY(?, 4674), '{}', 1, 1, 1)
                """, COD_IMOVEL, WKT_IMOVEL, WKT_IMOVEL);

        jdbc.update("""
                INSERT INTO silver.area_protegida
                    (codigo_origem, nome, tipo_area_protegida_id, orgao_id, geometria, atributos_origem,
                     fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                SELECT ?, 'UC de teste', tap.id, o.id, SDO_GEOMETRY(?, 4674), '{}', 1, 1, 1
                  FROM silver.tipo_area_protegida tap, silver.orgao o
                 WHERE tap.codigo = 'UC_PROTECAO_INTEGRAL' AND o.codigo = 'ICMBIO'
                """, COD_IMOVEL + "-AP", WKT_AREA_PROTEGIDA_OESTE);

        jdbc.update("""
                INSERT INTO silver.embargo_ambiental
                    (numero_termo, orgao_id, geometria, atributos_origem, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                SELECT ?, o.id, SDO_GEOMETRY(?, 4674), '{}', 1, 1, 1
                  FROM silver.orgao o WHERE o.codigo = 'IBAMA'
                """, COD_IMOVEL + "-EMB", WKT_EMBARGO_LESTE);

        jdbc.update("""
                INSERT INTO silver.desmatamento
                    (identificador_origem, ano, geometria, atributos_origem, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, 2022, SDO_GEOMETRY(?, 4674), '{}', 1, 1, 1)
                """, COD_IMOVEL + "-DESM", WKT_DESMATAMENTO_TOTAL);

        jdbc.update("""
                INSERT INTO silver.foco_calor
                    (identificador_origem, geometria, atributos_origem, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, SDO_GEOMETRY('POINT(-50.995 -23.995)', 4674), '{}', 1, 1, 1)
                """, COD_IMOVEL + "-FOCO-DENTRO");
        jdbc.update("""
                INSERT INTO silver.foco_calor
                    (identificador_origem, geometria, atributos_origem, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, SDO_GEOMETRY('POINT(-49.000 -22.000)', 4674), '{}', 1, 1, 1)
                """, COD_IMOVEL + "-FOCO-FORA");

        // LIZARDS-32 (V072)/LIZARDS-31 (V073) - area_ha calculada via SDO_GEOM.SDO_AREA
        // (não um valor arbitrário) pra bater com o que o cálculo real vai medir.
        jdbc.update("""
                INSERT INTO silver.reserva_legal
                    (identificador_origem, cod_imovel, area_ha, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, ?, SDO_GEOM.SDO_AREA(SDO_GEOMETRY(?, 4674), 0.05, 'unit=Hectare'), SDO_GEOMETRY(?, 4674), 1, 1, 1)
                """, COD_IMOVEL + "-RL", COD_IMOVEL, WKT_RESERVA_LEGAL_NORTE, WKT_RESERVA_LEGAL_NORTE);

        jdbc.update("""
                INSERT INTO silver.app_declarada
                    (identificador_origem, cod_imovel, area_ha, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, ?, SDO_GEOM.SDO_AREA(SDO_GEOMETRY(?, 4674), 0.05, 'unit=Hectare'), SDO_GEOMETRY(?, 4674), 1, 1, 1)
                """, COD_IMOVEL + "-APP", COD_IMOVEL, WKT_APP_DECLARADA_SUL, WKT_APP_DECLARADA_SUL);

        jdbc.update("""
                INSERT INTO silver.vegetacao_nativa_declarada
                    (identificador_origem, cod_imovel, area_ha, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id)
                VALUES (?, ?, SDO_GEOM.SDO_AREA(SDO_GEOMETRY(?, 4674), 0.05, 'unit=Hectare'), SDO_GEOMETRY(?, 4674), 1, 1, 1)
                """, COD_IMOVEL + "-VEG", COD_IMOVEL, WKT_VEGETACAO_NATIVA_OESTE, WKT_VEGETACAO_NATIVA_OESTE);
    }

    @Test
    void calculaOsSeteIndicadoresEPublicaOImovel() {
        service.recalcularImovel(COD_IMOVEL);

        Map<String, Map<String, Object>> resultados = jdbc.queryForList("""
                SELECT ti.codigo, ir.valor, ir.area_afetada_ha, ir.percentual_sobreposicao
                  FROM gold.indicador_resultado ir
                  JOIN gold.indicador_versao iv ON iv.id = ir.indicador_versao_id
                  JOIN gold.tipo_indicador ti ON ti.id = ir.tipo_indicador_id
                 WHERE iv.cod_imovel = ?
                """, COD_IMOVEL).stream()
                .collect(java.util.stream.Collectors.toMap(m -> (String) m.get("CODIGO"), m -> m));

        assertThat(resultados).containsKeys("ISAP", "IAE", "IDESMAT", "IFC", "IRL", "IAPP", "ICV");
        assertThat(((Number) resultados.get("ISAP").get("PERCENTUAL_SOBREPOSICAO")).doubleValue())
                .isCloseTo(50.0, within(3.0));
        assertThat(((Number) resultados.get("IAE").get("PERCENTUAL_SOBREPOSICAO")).doubleValue())
                .isCloseTo(25.0, within(3.0));
        assertThat(((Number) resultados.get("IDESMAT").get("PERCENTUAL_SOBREPOSICAO")).doubleValue())
                .isCloseTo(100.0, within(1.0));
        // IFC = 1 foco dentro / area_ha * 1000
        Number areaHa = jdbc.queryForObject(
                "SELECT area_ha FROM silver.imovel_rural WHERE cod_imovel = ?", Number.class, COD_IMOVEL);
        double ifcEsperado = 1.0 / areaHa.doubleValue() * 1000;
        assertThat(((Number) resultados.get("IFC").get("VALOR")).doubleValue())
                .isCloseTo(ifcEsperado, within(0.5));
        // IRL = reserva_legal (quarto NORTE) / area do imovel
        assertThat(((Number) resultados.get("IRL").get("PERCENTUAL_SOBREPOSICAO")).doubleValue())
                .isCloseTo(25.0, within(3.0));
        // ICV = vegetacao_nativa_declarada (metade OESTE) / area do imovel
        assertThat(((Number) resultados.get("ICV").get("PERCENTUAL_SOBREPOSICAO")).doubleValue())
                .isCloseTo(50.0, within(3.0));
        // IAPP = (APP sul ∩ vegetacao oeste = quarto sudoeste) / area da APP (metade sul)
        // = (1/4) / (1/2) = 50%
        assertThat(((Number) resultados.get("IAPP").get("PERCENTUAL_SOBREPOSICAO")).doubleValue())
                .isCloseTo(50.0, within(5.0));

        // Bloco 3: imovel_publicado com os 7 valores denormalizados, todos reais agora
        Map<String, Object> publicado = jdbc.queryForMap("""
                SELECT valor_isap, valor_iae, valor_idesmat, valor_ifc, valor_icv, valor_irl, valor_iapp
                  FROM gold.imovel_publicado WHERE cod_imovel = ?
                """, COD_IMOVEL);
        assertThat(((Number) publicado.get("VALOR_ISAP")).doubleValue()).isCloseTo(50.0, within(3.0));
        assertThat(((Number) publicado.get("VALOR_ICV")).doubleValue()).isCloseTo(50.0, within(3.0));
        assertThat(((Number) publicado.get("VALOR_IRL")).doubleValue()).isCloseTo(25.0, within(3.0));
        assertThat(((Number) publicado.get("VALOR_IAPP")).doubleValue()).isCloseTo(50.0, within(5.0));

        // Bloco 3b: sobreposicao_publicada com o detalhe de ISAP/IAE/IDesmat (nao IFC, nao e area)
        List<String> tiposRestricao = jdbc.queryForList("""
                SELECT sp.tipo_restricao FROM gold.sobreposicao_publicada sp
                  JOIN gold.imovel_publicado ip ON ip.id = sp.imovel_publicado_id
                 WHERE ip.cod_imovel = ?
                """, String.class, COD_IMOVEL);
        assertThat(tiposRestricao).containsExactlyInAnyOrder("AREA_PROTEGIDA", "EMBARGO", "DESMATAMENTO");
    }

    @Test
    void calculoENaoEncontrarImovelNaoLancaExcecao() {
        service.recalcularImovel("COD-IMOVEL-INEXISTENTE-XYZ");
        Long contagem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gold.indicador_versao WHERE cod_imovel = ?",
                Long.class, "COD-IMOVEL-INEXISTENTE-XYZ");
        assertThat(contagem).isZero();
    }
}
