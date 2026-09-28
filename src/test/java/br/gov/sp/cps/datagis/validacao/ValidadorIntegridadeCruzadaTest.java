package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class ValidadorIntegridadeCruzadaTest {

    private JdbcTemplate jdbcTemplate;
    private ValidadorIntegridadeCruzada validador;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validador = new ValidadorIntegridadeCruzada(jdbcTemplate, "SILVER");
    }

    // -------------------------------------------------------------------------
    // suporta()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar quando referenciaEntidadeSilver estiver preenchida")
    void deveSuportarComReferencia() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("cod_municipio_ibge")
                .referenciaEntidadeSilver("municipio")
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("Não deve suportar quando referenciaEntidadeSilver for nula ou em branco")
    void naoDeveSuportarSemReferencia(String ref) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("cod_municipio_ibge")
                .referenciaEntidadeSilver(ref)
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @Test
    @DisplayName("Não deve suportar contexto nulo")
    void naoDeveSuportarContextoNulo() {
        assertThat(validador.suporta(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // validar() — Entidade Pré-carregada (municipio)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar código de município existente em silver.municipio")
    void deveAprovarMunicipioExistente() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902", "4115200", "4119905"));

        ContextoValidacaoCampo ctx = contextoComRef("cod_municipio_ibge", "municipio");
        ResultadoValidacao resultado = validador.validar("4106902", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("4106902");
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve rejeitar código de município inexistente com erro REFERENCIA_NAO_ENCONTRADA")
    void deveRejeitarMunicipioInexistente() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902", "4115200"));

        ContextoValidacaoCampo ctx = contextoComRef("cod_municipio_ibge", "municipio");
        ResultadoValidacao resultado = validador.validar("4199999", ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("REFERENCIA_NAO_ENCONTRADA");
        assertThat(resultado.campoProblema()).isEqualTo("cod_municipio_ibge");
        assertThat(resultado.valorRecebido()).isEqualTo("4199999");
        assertThat(resultado.motivoRejeicao()).contains("não encontrada na entidade Silver \"municipio\"");
    }

    @Test
    @DisplayName("Deve consultar silver.municipio apenas uma vez aproveitando o cache")
    void deveUsarCachePreCarregadoMunicipio() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902", "4115200", "4119905"));

        ContextoValidacaoCampo ctx = contextoComRef("cod_municipio_ibge", "municipio");

        validador.validar("4106902", ctx);
        validador.validar("4115200", ctx);
        validador.validar("4119905", ctx);

        verify(jdbcTemplate, times(1)).queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class);
    }

    @Test
    @DisplayName("Deve aprovar município com prefixo PR- ou por nome oficial e rejeitar inexistente")
    void deveAprovarMunicipioComPrefixoOuNome() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902", "4108304"));
        when(jdbcTemplate.queryForList("SELECT nome FROM SILVER.municipio", String.class))
                .thenReturn(List.of("Curitiba", "Foz do Iguaçu"));

        validador.invalidarCache("municipio");
        ContextoValidacaoCampo ctx = contextoComRef("municipio", "municipio");

        // 1. Por código IBGE direto
        ResultadoValidacao r1 = validador.validar("4106902", ctx);
        assertThat(r1.isValido()).isTrue();

        // 2. Com prefixo PR-
        ResultadoValidacao r2 = validador.validar("PR-4106902", ctx);
        assertThat(r2.isValido()).isTrue();

        // 3. Por nome com acento
        ResultadoValidacao r3 = validador.validar("Foz do Iguaçu", ctx);
        assertThat(r3.isValido()).isTrue();

        // 4. Inexistente -> Erro REFERENCIA_NAO_ENCONTRADA
        ResultadoValidacao r4 = validador.validar("Municipio Fantasma", ctx);
        assertThat(r4.isValido()).isFalse();
        assertThat(r4.tipoErro()).isEqualTo("REFERENCIA_NAO_ENCONTRADA");
    }

    @Test
    @DisplayName("Deve validar integridade cruzada de auto_infracao (numero_auto) via consulta pontual")
    void deveValidarAutoInfracaoPontual() {
        String sql = "SELECT COUNT(1) FROM SILVER.auto_infracao WHERE numero_auto = ?";
        when(jdbcTemplate.queryForObject(eq(sql), eq(Integer.class), eq("AUTO-12345")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(eq(sql), eq(Integer.class), eq("AUTO-99999")))
                .thenReturn(0);

        ContextoValidacaoCampo ctx = contextoComRef("num_auto_infracao", "auto_infracao");

        // Existente
        ResultadoValidacao r1 = validador.validar("AUTO-12345", ctx);
        assertThat(r1.isValido()).isTrue();

        // Inexistente
        ResultadoValidacao r2 = validador.validar("AUTO-99999", ctx);
        assertThat(r2.isValido()).isFalse();
        assertThat(r2.tipoErro()).isEqualTo("REFERENCIA_NAO_ENCONTRADA");
        assertThat(r2.motivoRejeicao()).contains("não encontrada na entidade Silver \"auto_infracao\"");
    }

    // -------------------------------------------------------------------------
    // validar() — Entidade Dinâmica (pontual: imovel_rural, area_protegida)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar chave em imovel_rural via consulta pontual e cachear para chamadas seguintes")
    void deveAprovarImovelRuralPontual() {
        String sql = "SELECT COUNT(1) FROM SILVER.imovel_rural WHERE cod_imovel = ?";
        when(jdbcTemplate.queryForObject(eq(sql), eq(Integer.class), eq("PR-4106902-DEMO")))
                .thenReturn(1);

        ContextoValidacaoCampo ctx = contextoComRef("cod_imovel_ref", "imovel_rural");

        // 1ª chamada: consulta banco
        ResultadoValidacao r1 = validador.validar("PR-4106902-DEMO", ctx);
        assertThat(r1.isValido()).isTrue();

        // 2ª chamada: bate em cache
        ResultadoValidacao r2 = validador.validar("PR-4106902-DEMO", ctx);
        assertThat(r2.isValido()).isTrue();

        verify(jdbcTemplate, times(1)).queryForObject(eq(sql), eq(Integer.class), eq("PR-4106902-DEMO"));
    }

    @Test
    @DisplayName("Deve rejeitar chave inexistente em consulta pontual")
    void deveRejeitarChaveInexistentePontual() {
        String sql = "SELECT COUNT(1) FROM SILVER.imovel_rural WHERE cod_imovel = ?";
        when(jdbcTemplate.queryForObject(eq(sql), eq(Integer.class), eq("PR-INEXISTENTE")))
                .thenReturn(0);

        ContextoValidacaoCampo ctx = contextoComRef("cod_imovel_ref", "imovel_rural");
        ResultadoValidacao resultado = validador.validar("PR-INEXISTENTE", ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("REFERENCIA_NAO_ENCONTRADA");
    }

    // -------------------------------------------------------------------------
    // Especificação de coluna explícita e prefixos de schema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar referência com especificação explícita de coluna (ex.: silver.municipio.cod_ibge)")
    void deveSuportarColunaExplicita() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902"));

        ContextoValidacaoCampo ctx = contextoComRef("cod_ibge", "silver.municipio.cod_ibge");
        ResultadoValidacao resultado = validador.validar("4106902", ctx);

        assertThat(resultado.isValido()).isTrue();
    }

    @Test
    @DisplayName("Deve suportar entidade com convenção de coluna personalizada")
    void deveSuportarEntidadePersonalizada() {
        String sql = "SELECT COUNT(1) FROM SILVER.outra_tabela WHERE chave_customizada = ?";
        when(jdbcTemplate.queryForObject(eq(sql), eq(Integer.class), eq("CHAVE123")))
                .thenReturn(1);

        ContextoValidacaoCampo ctx = contextoComRef("codigo", "outra_tabela.chave_customizada");
        ResultadoValidacao resultado = validador.validar("CHAVE123", ctx);

        assertThat(resultado.isValido()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Casos de borda (Nulo, branco, SQL injection)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve retornar sucesso sem erro para valor nulo (responsabilidade do SanitizadorEntrada)")
    void deveIgnorarValorNulo() {
        ContextoValidacaoCampo ctx = contextoComRef("cod_municipio_ibge", "municipio");
        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("Deve retornar sucesso sem erro para string vazia ou espaços")
    void deveIgnorarStringVazia(String valor) {
        ContextoValidacaoCampo ctx = contextoComRef("cod_municipio_ibge", "municipio");
        ResultadoValidacao resultado = validador.validar(valor, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "municipio; DROP TABLE usuarios;",
            "municipio' OR 1=1--",
            "../municipio",
            "tabela com espacos"
    })
    @DisplayName("Deve rejeitar identificadores com caracteres ilegais para proteção contra SQL Injection")
    void deveRejeitarSqlInjection(String refInvalida) {
        ContextoValidacaoCampo ctx = contextoComRef("campo", refInvalida);

        assertThatThrownBy(() -> validador.validar("VALOR", ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido ou suspeito");
    }

    // -------------------------------------------------------------------------
    // Invalidação e Ação do Analista
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Ação do Analista: deve invalidar cache e reconhecer nova referência após aprovação")
    void deveRevalidarAposInvalidacaoDeCache() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902")) // inicial sem 4115200
                .thenReturn(List.of("4106902", "4115200")); // após atualização

        ContextoValidacaoCampo ctx = contextoComRef("cod_municipio_ibge", "municipio");

        // 1ª checagem: 4115200 rejeitado
        ResultadoValidacao r1 = validador.validar("4115200", ctx);
        assertThat(r1.isValido()).isFalse();

        // Analista cadastra novo município ou aprova revalidação
        validador.invalidarCache("municipio");
        assertThat(validador.isEmCache("municipio")).isFalse();

        // 2ª checagem: recarrega do banco e aprova 4115200
        ResultadoValidacao r2 = validador.validar("4115200", ctx);
        assertThat(r2.isValido()).isTrue();

        verify(jdbcTemplate, times(2)).queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class);
    }

    @Test
    @DisplayName("invalidarTodoCache deve limpar todas as referências em memória")
    void deveLimparTodoCache() {
        when(jdbcTemplate.queryForList("SELECT cod_ibge FROM SILVER.municipio", String.class))
                .thenReturn(List.of("4106902"));

        validador.validar("4106902", contextoComRef("cod_ibge", "municipio"));
        assertThat(validador.isEmCache("municipio")).isTrue();

        validador.invalidarTodoCache();
        assertThat(validador.isEmCache("municipio")).isFalse();
        assertThat(validador.getQuantidadeEntidadesEmCache()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ContextoValidacaoCampo contextoComRef(String coluna, String ref) {
        return ContextoValidacaoCampo.builder()
                .colunaOrigem(coluna)
                .referenciaEntidadeSilver(ref)
                .build();
    }
}
