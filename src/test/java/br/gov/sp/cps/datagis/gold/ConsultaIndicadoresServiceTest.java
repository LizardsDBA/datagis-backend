package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Testes da consulta de indicadores (LIZARDS-34).
 *
 * <p>
 * O service nao tem regra de negocio em Java: a regra esta no SQL que ele
 * monta.
 * Entao o teste faz duas coisas. Primeiro captura o SQL enviado ao JdbcTemplate
 * e
 * confere que ele consulta a Gold, faz o join com a Silver e agrupa com media
 * ponderada. Depois captura o RowMapper e executa ele contra um ResultSet
 * simulado,
 * que e onde se prova a parte mais facil de quebrar: indicador sem valor tem
 * que
 * virar celula vazia, nunca zero.
 */
class ConsultaIndicadoresServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ConsultaIndicadoresService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ConsultaIndicadoresService(jdbcTemplate, "GOLD", "SILVER");
    }

    // ------------------------------------------------------------------
    // SQL gerado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Visao por imovel deve ler a Gold e buscar o nome do municipio na Silver")
    void visaoPorImovelDeveCruzarGoldComSilver() {
        String sql = sqlDe(() -> service.listarPorImovel());

        assertThat(sql).contains("FROM GOLD.imovel_publicado p");
        assertThat(sql).contains("LEFT JOIN SILVER.municipio m ON m.cod_ibge = i.municipio_cod_ibge");
        assertThat(sql).contains("ORDER BY m.nome NULLS LAST, i.cod_imovel");
    }

    // imovel_publicado guarda uma linha por versao publicada. Sem o filtro de versao, um
    // imovel republicado aparece duas vezes e a area dele entra em dobro na media do
    // municipio (achado testando contra o Oracle real).

    @Test
    @DisplayName("Visao por imovel deve ler so a versao mais recente de cada imovel")
    void visaoPorImovelDeveLerSoAVersaoMaisRecente() {
        assertFiltraVersaoMaisRecente(sqlDe(() -> service.listarPorImovel()));
    }

    @Test
    @DisplayName("Visao por municipio deve agregar so a versao mais recente de cada imovel")
    void visaoPorMunicipioDeveAgregarSoAVersaoMaisRecente() {
        assertFiltraVersaoMaisRecente(sqlDe(() -> service.listarPorMunicipio()));
    }

    private static void assertFiltraVersaoMaisRecente(String sql) {
        assertThat(sql).contains(
                "ROW_NUMBER() OVER (PARTITION BY p.cod_imovel ORDER BY p.data_publicacao DESC, p.id DESC)");
        assertThat(sql).contains("WHERE i.ordem_versao = 1");
    }

    @Test
    @DisplayName("Visao por imovel deve trazer os sete indicadores")
    void visaoPorImovelDeveTrazerOsSeteIndicadores() {
        String sql = sqlDe(() -> service.listarPorImovel());

        assertThat(sql)
                .contains("i.valor_icv")
                .contains("i.valor_irl")
                .contains("i.valor_iapp")
                .contains("i.valor_isap")
                .contains("i.valor_iae")
                .contains("i.valor_idesmat")
                .contains("i.valor_ifc");
    }

    @Test
    @DisplayName("Visao por municipio deve agrupar e contar imoveis e area")
    void visaoPorMunicipioDeveAgrupar() {
        String sql = sqlDe(() -> service.listarPorMunicipio());

        assertThat(sql).contains("GROUP BY i.municipio_cod_ibge");
        assertThat(sql).contains("COUNT(*) AS qtd_imoveis");
        assertThat(sql).contains("ROUND(SUM(NVL(i.area_ha, 0)), 2) AS area_total_ha");
        // MAX(m.nome) e necessario porque o nome nao esta no GROUP BY.
        assertThat(sql).contains("MAX(m.nome) AS nome");
    }

    @Test
    @DisplayName("Media por municipio deve ser ponderada pela area, e nao media simples")
    void mediaPorMunicipioDeveSerPonderadaPelaArea() {
        String sql = sqlDe(() -> service.listarPorMunicipio());

        for (String coluna : new String[] {
                "valor_icv", "valor_irl", "valor_iapp", "valor_isap",
                "valor_iae", "valor_idesmat", "valor_ifc" }) {
            // Numerador: indicador multiplicado pela area do imovel.
            assertThat(sql).contains("SUM(i." + coluna + " * NVL(i.area_ha, 0))");
            // Denominador: so a area dos imoveis que tem valor naquele indicador.
            assertThat(sql).contains(
                    "NULLIF(SUM(CASE WHEN i." + coluna + " IS NOT NULL THEN NVL(i.area_ha, 0) END), 0)");
            assertThat(sql).contains("AS " + coluna);
        }
        // Media simples seria AVG: se aparecer, a ponderacao foi perdida.
        assertThat(sql).doesNotContain("AVG(");
    }

    @Test
    @DisplayName("Deve usar os schemas configurados, sem nome de schema fixo no codigo")
    void deveUsarOsSchemasConfigurados() {
        ConsultaIndicadoresService outro = new ConsultaIndicadoresService(jdbcTemplate, "GOLD_HOM", "SILVER_HOM");

        String sql = sqlDe(() -> outro.listarPorMunicipio());

        assertThat(sql).contains("GOLD_HOM.imovel_publicado");
        assertThat(sql).contains("SILVER_HOM.municipio");
    }

    @Test
    @DisplayName("Deve devolver lista vazia quando nao houver imovel publicado")
    void deveDevolverListaVaziaQuandoNaoHaDado() {
        stubConsultaVazia();

        assertThat(service.listarPorMunicipio()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Mapeamento das linhas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Deve mapear uma linha completa da visao por imovel")
    void deveMapearLinhaCompletaPorImovel() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("cod_imovel")).thenReturn("PR-4106902-00B2EA81503E46B8BD95A7E35BBB8472");
        when(rs.getLong("municipio_cod_ibge")).thenReturn(4106902L);
        when(rs.getString("municipio_nome")).thenReturn("Curitiba");
        when(rs.getDouble("area_ha")).thenReturn(8.89);
        when(rs.getDouble("valor_icv")).thenReturn(15.54);
        when(rs.getDouble("valor_idesmat")).thenReturn(0.0);
        when(rs.getTimestamp("data_publicacao"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 10, 16, 23, 30)));
        when(rs.wasNull()).thenReturn(false);

        ConsultaImovelResponse linha = this.<ConsultaImovelResponse>mapperDe(() -> service.listarPorImovel()).mapRow(rs,
                1);

        assertThat(linha.codImovel()).isEqualTo("PR-4106902-00B2EA81503E46B8BD95A7E35BBB8472");
        assertThat(linha.municipioCodIbge()).isEqualTo(4106902L);
        assertThat(linha.municipioNome()).isEqualTo("Curitiba");
        assertThat(linha.areaHa()).isEqualTo(8.89);
        assertThat(linha.valorIcv()).isEqualTo(15.54);
        // Zero calculado e um valor legitimo e precisa continuar zero.
        assertThat(linha.valorIdesmat()).isEqualTo(0.0);
        assertThat(linha.dataPublicacao()).isEqualTo(LocalDateTime.of(2026, 10, 16, 23, 30));
    }

    @Test
    @DisplayName("Indicador sem valor deve virar nulo, e nao zero")
    void indicadorSemValorDeveVirarNulo() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("cod_imovel")).thenReturn("PR-4106902-ABC");
        when(rs.getLong("municipio_cod_ibge")).thenReturn(4106902L);
        when(rs.getDouble("area_ha")).thenReturn(8.89);
        when(rs.getDouble("valor_icv")).thenReturn(0.0);
        when(rs.getDouble("valor_irl")).thenReturn(6.05);
        // wasNull e consultado uma vez por coluna numerica, na ordem em que o record e
        // montado: municipio_cod_ibge, area_ha, icv, irl, iapp, isap, iae, idesmat,
        // ifc.
        // A terceira chamada e a do ICV: so ela responde que o campo era NULL.
        when(rs.wasNull()).thenReturn(false, false, true, false, false, false, false, false, false);

        ConsultaImovelResponse linha = this.<ConsultaImovelResponse>mapperDe(() -> service.listarPorImovel()).mapRow(rs,
                1);

        assertThat(linha.valorIcv()).isNull();
        assertThat(linha.areaHa()).isEqualTo(8.89);
        assertThat(linha.valorIrl()).isEqualTo(6.05);
    }

    @Test
    @DisplayName("Imovel sem data de publicacao nao deve quebrar o mapeamento")
    void imovelSemDataDePublicacaoNaoDeveQuebrar() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("cod_imovel")).thenReturn("PR-4106902-ABC");
        when(rs.getTimestamp("data_publicacao")).thenReturn(null);
        when(rs.wasNull()).thenReturn(true);

        ConsultaImovelResponse linha = this.<ConsultaImovelResponse>mapperDe(() -> service.listarPorImovel()).mapRow(rs,
                1);

        assertThat(linha.dataPublicacao()).isNull();
    }

    @Test
    @DisplayName("Deve mapear uma linha da visao por municipio")
    void deveMapearLinhaPorMunicipio() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("cod_ibge")).thenReturn(4119905L);
        when(rs.getString("nome")).thenReturn("Ponta Grossa");
        when(rs.getLong("qtd_imoveis")).thenReturn(2657L);
        when(rs.getDouble("area_total_ha")).thenReturn(187029.84);
        when(rs.getDouble("valor_icv")).thenReturn(27.31);
        when(rs.getDouble("valor_iae")).thenReturn(0.23);
        when(rs.wasNull()).thenReturn(false);

        ConsultaMunicipioResponse linha = this.<ConsultaMunicipioResponse>mapperDe(() -> service.listarPorMunicipio())
                .mapRow(rs, 1);

        assertThat(linha.codIbge()).isEqualTo(4119905L);
        assertThat(linha.nome()).isEqualTo("Ponta Grossa");
        assertThat(linha.qtdImoveis()).isEqualTo(2657L);
        assertThat(linha.areaTotalHa()).isEqualTo(187029.84);
        assertThat(linha.valorIcv()).isEqualTo(27.31);
        assertThat(linha.valorIae()).isEqualTo(0.23);
    }

    @Test
    @DisplayName("Municipio cujo indicador nao pode ser calculado deve vir nulo")
    void municipioComIndicadorNaoCalculadoDeveVirNulo() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("cod_ibge")).thenReturn(4115200L);
        when(rs.getString("nome")).thenReturn("Maringa");
        when(rs.getLong("qtd_imoveis")).thenReturn(1843L);
        when(rs.getDouble("area_total_ha")).thenReturn(35839.45);
        when(rs.getDouble("valor_icv")).thenReturn(8.30);
        // Ordem das chamadas: cod_ibge, area_total_ha, icv, irl, iapp, isap, iae,
        // idesmat, ifc. A quarta e a do IRL (NULLIF devolveu NULL: nenhum imovel do
        // municipio tem esse indicador calculado).
        when(rs.wasNull()).thenReturn(false, false, false, true, false, false, false, false, false);

        ConsultaMunicipioResponse linha = this.<ConsultaMunicipioResponse>mapperDe(() -> service.listarPorMunicipio())
                .mapRow(rs, 1);

        assertThat(linha.valorIcv()).isEqualTo(8.30);
        assertThat(linha.valorIrl()).isNull();
        assertThat(linha.qtdImoveis()).isEqualTo(1843L);
    }

    // ------------------------------------------------------------------
    // Apoio
    // ------------------------------------------------------------------

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void stubConsultaVazia() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(Collections.emptyList());
    }

    /** Executa a consulta com o banco simulado e devolve o SQL que ela montou. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private String sqlDe(Runnable consulta) {
        stubConsultaVazia();
        consulta.run();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class));
        return sql.getValue();
    }

    /**
     * Executa a consulta com o banco simulado e devolve o RowMapper que ela usou.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T> RowMapper<T> mapperDe(Runnable consulta) {
        stubConsultaVazia();
        consulta.run();

        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), mapper.capture());
        return mapper.getValue();
    }
}