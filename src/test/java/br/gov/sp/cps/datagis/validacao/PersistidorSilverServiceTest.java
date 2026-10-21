package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class PersistidorSilverServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PersistidorSilverService persistidor;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(any(String.class), any(org.springframework.jdbc.core.PreparedStatementCallback.class))).thenReturn(1);
        persistidor = new PersistidorSilverService(jdbcTemplate, "SILVER", "CONTROLE", null);
    }

    @Test
    @DisplayName("Deve resolver id de referência quando valor recebido for numérico direto")
    void deveResolverIdNumericoDireto() {
        Long id = persistidor.resolverIdReferencia("condicao_imovel", 5L);
        assertThat(id).isEqualTo(5L);

        Long idStr = persistidor.resolverIdReferencia("condicao_imovel", "3");
        assertThat(idStr).isEqualTo(3L);
    }

    @Test
    @DisplayName("Deve resolver id de condicao_imovel com acento (ex.: 'Aguardando análise' -> 1)")
    void deveResolverCondicaoComAcento() throws SQLException {
        mockTabelaCondicaoImovel();

        // 1. Com acento
        Long idAguardando = persistidor.resolverIdReferencia("condicao_imovel", "Aguardando análise");
        assertThat(idAguardando).isEqualTo(1L);

        // 2. Sem acento
        Long idSemAcento = persistidor.resolverIdReferencia("condicao_imovel", "Aguardando analise");
        assertThat(idSemAcento).isEqualTo(1L);

        // 3. Pelo código canônico
        Long idCodigo = persistidor.resolverIdReferencia("condicao_imovel", "AGUARDANDO_ANALISE");
        assertThat(idCodigo).isEqualTo(1L);

        // 4. Cancelado por decisão administrativa
        Long idCancelado = persistidor.resolverIdReferencia("condicao_imovel", "Cancelado por decisão administrativa");
        assertThat(idCancelado).isEqualTo(5L);
    }

    @Test
    @DisplayName("Deve resolver id de descrições da Lei 12.651/2012 do SICAR")
    void deveResolverDescricoesSicarLei() throws SQLException {
        mockTabelaCondicaoImovel();

        Long idSemPendencias = persistidor.resolverIdReferencia(
                "condicao_imovel", "Analisado, em conformidade com a Lei nº 12.651/2012");
        assertThat(idSemPendencias).isEqualTo(3L);

        Long idComPendencias = persistidor.resolverIdReferencia(
                "condicao_imovel", "Analisado, aguardando regularização ambiental (Lei nº 12.651/2012)");
        assertThat(idComPendencias).isEqualTo(4L);

        Long idNotificacao = persistidor.resolverIdReferencia(
                "condicao_imovel", "Analisado, aguardando atendimento a notificação");
        assertThat(idNotificacao).isEqualTo(4L);

        Long idRevisao = persistidor.resolverIdReferencia(
                "condicao_imovel", "Aguardando análise, após revisão ou atendimento da notificação");
        assertThat(idRevisao).isEqualTo(1L);
    }

    @Test
    @DisplayName("Deve resolver id de tipo_imovel com acento (ex.: 'Imóvel Rural' -> 1)")
    void deveResolverTipoImovelComAcento() throws SQLException {
        mockTabelaTipoImovel();

        Long idRural = persistidor.resolverIdReferencia("tipo_imovel", "Imóvel Rural");
        assertThat(idRural).isEqualTo(1L);

        Long idCodigo = persistidor.resolverIdReferencia("tipo_imovel", "IRU");
        assertThat(idCodigo).isEqualTo(1L);
    }

    @Test
    @DisplayName("Deve extrair e persistir auto de infração com houve_embargo, houve_apreensao e orgao da fonte")
    void devePersistirAutoInfracaoComNovosCampos() {
        // Mock da busca do orgao da fonte no schema controle
        when(jdbcTemplate.query(
                eq("SELECT orgao FROM CONTROLE.fonte_dado WHERE id = ?"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(10L)))
                .thenReturn(java.util.List.of("IBAMA"));

        br.gov.sp.cps.datagis.bronze.RegistroBruto reg = br.gov.sp.cps.datagis.bronze.RegistroBruto.builder()
                .id(100L)
                .fonteDadoId(10L)
                .loteIngestaoId(1L)
                .payload("{\"numero_auto\":\"AI-999\",\"houve_embargo\":\"S\",\"houve_apreensao\":\"SIM\"}")
                .build();

        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("numero_auto", "AI-999");
        attrs.put("houve_embargo", "S");
        attrs.put("houve_apreensao", "SIM");
        attrs.put("valor_multa", 1500.50);

        RegistroAprovadoSilver apr = new RegistroAprovadoSilver(reg, attrs, null);

        persistidor.persistirAutoInfracao(apr);

        org.mockito.Mockito.verify(jdbcTemplate).execute(
                org.mockito.ArgumentMatchers.contains("MERGE INTO SILVER.auto_infracao"),
                any(org.springframework.jdbc.core.PreparedStatementCallback.class));
    }

    @Test
    @DisplayName("Deve extrair e persistir foco de calor com dias_sem_chuva, precipitacao_mm e frp")
    void devePersistirFocoCalorComNovosCampos() {
        br.gov.sp.cps.datagis.bronze.RegistroBruto reg = br.gov.sp.cps.datagis.bronze.RegistroBruto.builder()
                .id(200L)
                .fonteDadoId(7L)
                .loteIngestaoId(1L)
                .payload("{\"id\":\"FOCO-123\",\"dias_sem_chuva\":\"5\",\"precipitacao_mm\":\"0.0\",\"frp\":\"12.4\"}")
                .build();

        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("identificador_origem", "FOCO-123");
        attrs.put("dias_sem_chuva", 5);
        attrs.put("precipitacao_mm", 0.0);
        attrs.put("frp", 12.4);

        RegistroAprovadoSilver apr = new RegistroAprovadoSilver(reg, attrs, "POINT(-51.2 -24.5)");

        persistidor.persistirFocoCalor(apr);

        org.mockito.Mockito.verify(jdbcTemplate).execute(
                org.mockito.ArgumentMatchers.contains("MERGE INTO SILVER.foco_calor"),
                any(org.springframework.jdbc.core.PreparedStatementCallback.class));
    }

    private void mockTabelaCondicaoImovel() throws SQLException {
        doAnswer(invocation -> {
            RowCallbackHandler rch = invocation.getArgument(1);
            rch.processRow(mockRow(1L, "AGUARDANDO_ANALISE", "Aguardando analise"));
            rch.processRow(mockRow(2L, "EM_ANALISE", "Em analise"));
            rch.processRow(mockRow(3L, "ANALISADO_SEM_PENDENCIAS", "Analisado sem pendencias"));
            rch.processRow(mockRow(4L, "ANALISADO_COM_PENDENCIAS", "Analisado com pendencias"));
            rch.processRow(mockRow(5L, "CANCELADO_ADM", "Cancelado por decisao administrativa"));
            rch.processRow(mockRow(6L, "CANCELADO_JUDICIAL", "Cancelado por decisao judicial"));
            return null;
        }).when(jdbcTemplate).query(eq("SELECT id, codigo, descricao FROM SILVER.condicao_imovel WHERE ativo = 1"), any(RowCallbackHandler.class));
    }

    private void mockTabelaTipoImovel() throws SQLException {
        doAnswer(invocation -> {
            RowCallbackHandler rch = invocation.getArgument(1);
            rch.processRow(mockRow(1L, "IRU", "Imovel Rural"));
            rch.processRow(mockRow(2L, "AST", "Assentamento de Reforma Agraria"));
            rch.processRow(mockRow(3L, "PCT", "Territorio de Povos e Comunidades Tradicionais"));
            return null;
        }).when(jdbcTemplate).query(eq("SELECT id, codigo, descricao FROM SILVER.tipo_imovel WHERE ativo = 1"), any(RowCallbackHandler.class));
    }

    private ResultSet mockRow(long id, String codigo, String descricao) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("codigo")).thenReturn(codigo);
        when(rs.getString("descricao")).thenReturn(descricao);
        return rs;
    }

    private static <T> org.mockito.stubbing.OngoingStubbing<T> when(T methodCall) {
        return org.mockito.Mockito.when(methodCall);
    }
}
