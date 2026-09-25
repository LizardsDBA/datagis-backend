package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DominioServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ValidadorDominio validadorDominio;
    private PersistidorSilverService persistidorSilverService;
    private DominioService dominioService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validadorDominio = mock(ValidadorDominio.class);
        persistidorSilverService = mock(PersistidorSilverService.class);
        dominioService = new DominioService(jdbcTemplate, validadorDominio, persistidorSilverService, "SILVER", "QUARENTENA");
    }

    @Test
    @DisplayName("Deve rejeitar requisição nula")
    void deveRejeitarRequisicaoNula() {
        assertThatThrownBy(() -> dominioService.adicionarValorDominio(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não pode ser nula");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unidade_federativa", "uf", "imovel_rural", "municipio", "auto_infracao", "tabela_inexistente", ""})
    @DisplayName("Deve rejeitar tabelas não autorizadas ou estáticas como unidade_federativa")
    void deveRejeitarTabelasNaoAutorizadas(String tabela) {
        NovoDominioRequest req = new NovoDominioRequest(tabela, "COD", "Desc", null, null, null, null, 1L);
        assertThatThrownBy(() -> dominioService.adicionarValorDominio(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Deve rejeitar descrição em branco")
    void deveRejeitarDescricaoEmBranco() {
        NovoDominioRequest req = new NovoDominioRequest("tipo_auto_infracao", "COD", "   ", null, null, null, null, 1L);
        assertThatThrownBy(() -> dominioService.adicionarValorDominio(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descrição");
    }

    @Test
    @DisplayName("Deve adicionar termo em tabela padrão e invalidar cache")
    void deveAdicionarEmTabelaPadrao() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(10L);

        NovoDominioRequest req = new NovoDominioRequest("tipo_auto_infracao", "DESTRUICAO", "Destruição de Flora", null, null, null, null, 1L);
        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("status")).isEqualTo("CRIADO");
        assertThat(resp.get("tabela")).isEqualTo("tipo_auto_infracao");
        assertThat(resp.get("codigo")).isEqualTo("DESTRUICAO");
        assertThat(resp.get("descricao")).isEqualTo("Destruição de Flora");
        assertThat(resp.get("id")).isEqualTo(10L);

        verify(validadorDominio).invalidarCache("tipo_auto_infracao");
        verify(persistidorSilverService).invalidarCache("tipo_auto_infracao");
    }

    @Test
    @DisplayName("Deve gerar código padronizado (slug) automaticamente quando não fornecido")
    void deveGerarCodigoAutomatico() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(15L);

        NovoDominioRequest req = new NovoDominioRequest("bioma", null, "Mata Atlântica & Restinga", null, null, null, null, 1L);
        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("codigo")).isEqualTo("MATA_ATLANTICA_RESTINGA");
        assertThat(resp.get("status")).isEqualTo("CRIADO");
    }

    @Test
    @DisplayName("Deve vincular categoria_area_protegida com tipo_area_protegida existente")
    void deveVincularCategoriaComTipoExistente() {
        // Mock tipo_area_protegida existente com id 2L
        when(jdbcTemplate.query(contains("tipo_area_protegida"), any(RowMapper.class), any(), any()))
                .thenReturn(List.of(2L));
        // Mock categoria inexistente
        when(jdbcTemplate.queryForList(contains("categoria_area_protegida"), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("categoria_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(100L);

        NovoDominioRequest req = new NovoDominioRequest(
                "categoria_area_protegida",
                "NOVA_CAT",
                "Nova Categoria",
                "UC_USO_SUSTENTAVEL",
                null,
                null,
                "area_protegida",
                1L
        );

        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("status")).isEqualTo("CRIADO");
        assertThat(resp.get("tipo_area_protegida_id")).isEqualTo(2L);
        assertThat(resp.get("id")).isEqualTo(100L);

        verify(validadorDominio).invalidarCache("categoria_area_protegida");
        verify(validadorDominio).invalidarCache("tipo_area_protegida");
    }

    @Test
    @DisplayName("Deve criar tipo_area_protegida primeiro quando ele não existir e depois vincular categoria")
    void deveCriarTipoPrimeiroQuandoNaoExistir() {
        // Mock tipo inexistente inicialmente, depois criado com id 3L
        when(jdbcTemplate.query(contains("tipo_area_protegida"), any(RowMapper.class), any(), any()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("tipo_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(3L);

        // Mock categoria inexistente, depois criada com id 101L
        when(jdbcTemplate.queryForList(contains("categoria_area_protegida"), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("categoria_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(101L);

        NovoDominioRequest req = new NovoDominioRequest(
                "categoria_area_protegida",
                "CATEGORIA_INEDITA",
                "Categoria Inédita",
                "TIPO_INEDITO",
                null,
                null,
                "area_protegida",
                1L
        );

        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("tipo_area_protegida_id")).isEqualTo(3L);
        assertThat(resp.get("tipo_auto_criado_id")).isEqualTo(3L);
        assertThat(resp.get("id")).isEqualTo(101L);

        verify(validadorDominio).invalidarCache("categoria_area_protegida");
        verify(validadorDominio).invalidarCache("tipo_area_protegida");
    }

    @Test
    @DisplayName("Deve criar tipo_area_protegida e vincular com categoria fornecida na mesma linha")
    void deveCriarTipoEVincularCategoria() {
        // Mock tipo criado com id 4L
        when(jdbcTemplate.queryForList(contains("tipo_area_protegida"), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("tipo_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(4L);

        // Mock categoria criada vinculada com id 202L
        when(jdbcTemplate.query(contains("categoria_area_protegida"), any(RowMapper.class), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("categoria_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(202L);

        NovoDominioRequest req = new NovoDominioRequest(
                "tipo_area_protegida",
                "NOVO_TIPO_AP",
                "Novo Tipo AP",
                null,
                "CATEGORIA_DA_LINHA",
                null,
                "area_protegida",
                1L
        );

        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("id")).isEqualTo(4L);
        assertThat(resp.get("categoria_vinculada_id")).isEqualTo(202L);

        verify(validadorDominio).invalidarCache("tipo_area_protegida");
        verify(validadorDominio).invalidarCache("categoria_area_protegida");
    }

    @Test
    @DisplayName("Deve vincular categoria com tipo_area_protegida selecionado por ID")
    void deveVincularCategoriaComTipoSelecionadoPorId() {
        // Mock busca por ID do tipo retornando ID 5L
        when(jdbcTemplate.query(contains("tipo_area_protegida WHERE id = ?"), any(RowMapper.class), eq(5L)))
                .thenReturn(List.of(5L));
        // Mock categoria inexistente
        when(jdbcTemplate.queryForList(contains("categoria_area_protegida"), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("categoria_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(300L);

        NovoDominioRequest req = new NovoDominioRequest(
                "categoria_area_protegida",
                "CAT_ID_TESTE",
                "Categoria com Tipo ID",
                null,
                5L,
                false,
                null,
                null,
                null,
                null,
                "area_protegida",
                1L
        );

        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("id")).isEqualTo(300L);
        assertThat(resp.get("tipo_area_protegida_id")).isEqualTo(5L);
        assertThat(resp.get("status")).isEqualTo("CRIADO");
    }

    @Test
    @DisplayName("Deve criar novo tipo_area_protegida e categoria quando criarNovoTipo for true")
    void deveCriarNovoTipoECategoriaQuandoSolicitado() {
        // Mock busca novo tipo inexistente
        when(jdbcTemplate.queryForList(contains("tipo_area_protegida WHERE UPPER(codigo)"), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("tipo_area_protegida WHERE UPPER(codigo)"), eq(Long.class), anyString()))
                .thenReturn(8L);

        // Mock categoria inexistente
        when(jdbcTemplate.queryForList(contains("categoria_area_protegida"), anyString()))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("categoria_area_protegida"), eq(Long.class), anyString()))
                .thenReturn(400L);

        NovoDominioRequest req = new NovoDominioRequest(
                "categoria_area_protegida",
                "CAT_NOVA_COM_TIPO",
                "Categoria com Novo Tipo",
                null,
                null,
                true,
                "NOVO_TIPO_COD",
                "Novo Tipo Desc",
                null,
                null,
                "area_protegida",
                1L
        );

        Map<String, Object> resp = dominioService.adicionarValorDominio(req);

        assertThat(resp.get("id")).isEqualTo(400L);
        assertThat(resp.get("tipo_area_protegida_id")).isEqualTo(8L);
        assertThat(resp.get("tipo_status")).isEqualTo("CRIADO");
    }

    @Test
    @DisplayName("Deve listar tipos de área protegida ativos")
    void deveListarTiposAreaProtegidaAtivos() {
        when(jdbcTemplate.queryForList(contains("SELECT id, codigo, descricao FROM SILVER.tipo_area_protegida")))
                .thenReturn(List.of(
                        Map.of("id", 1L, "codigo", "UC_PROTECAO_INTEGRAL", "descricao", "Proteção Integral"),
                        Map.of("id", 2L, "codigo", "UC_USO_SUSTENTAVEL", "descricao", "Uso Sustentável")
                ));

        List<Map<String, Object>> lista = dominioService.listarTiposAreaProtegida();
        assertThat(lista).hasSize(2);
        assertThat(lista.get(0).get("codigo")).isEqualTo("UC_PROTECAO_INTEGRAL");
    }
}
