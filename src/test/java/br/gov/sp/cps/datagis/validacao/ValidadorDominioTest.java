package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class ValidadorDominioTest {

    private JdbcTemplate jdbcTemplate;
    private ValidadorDominio validador;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validador = new ValidadorDominio(jdbcTemplate, "SILVER");
    }

    // -------------------------------------------------------------------------
    // suporta()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar quando tabelaReferencia estiver preenchida")
    void deveSuportarComTabelaReferencia() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("situacao")
                .tabelaReferencia("situacao_imovel")
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("Não deve suportar quando tabelaReferencia for nula ou em branco")
    void naoDeveSuportarSemTabelaReferencia(String tabela) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("situacao")
                .tabelaReferencia(tabela)
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @Test
    @DisplayName("Não deve suportar contexto nulo")
    void naoDeveSuportarContextoNulo() {
        assertThat(validador.suporta(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // validar() — Sucesso e Normalização
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar valor existente pelo código exato e retornar código canônico")
    void deveAprovarPeloCodigoExato() {
        configurarMockSituacaoImovel();

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");
        ResultadoValidacao resultado = validador.validar("AT", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("AT");
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar valor existente em minúsculas (case-insensitive) e retornar código canônico em maiúsculas")
    void deveAprovarCaseInsensitive() {
        configurarMockSituacaoImovel();

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");
        ResultadoValidacao resultado = validador.validar("pe", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("PE");
    }

    @Test
    @DisplayName("Deve aprovar valor informado pela descrição e mapear para o código canônico")
    void deveAprovarPelaDescricao() {
        configurarMockSituacaoImovel();

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");
        ResultadoValidacao resultado = validador.validar("Ativo", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("AT");
    }

    @Test
    @DisplayName("Deve suportar tabelaReferencia com prefixo de schema (ex.: silver.situacao_imovel)")
    void deveSuportarPrefixoSchema() {
        configurarMockSituacaoImovel();

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "silver.situacao_imovel");
        ResultadoValidacao resultado = validador.validar("CA", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("CA");
    }

    // -------------------------------------------------------------------------
    // validar() — Rejeição / Quarentena
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve rejeitar valor inexistente com erro VALOR_FORA_DO_DOMINIO")
    void deveRejeitarValorInexistente() {
        configurarMockSituacaoImovel();

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");
        ResultadoValidacao resultado = validador.validar("INEXISTENTE", ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("VALOR_FORA_DO_DOMINIO");
        assertThat(resultado.campoProblema()).isEqualTo("situacao");
        assertThat(resultado.valorRecebido()).isEqualTo("INEXISTENTE");
        assertThat(resultado.motivoRejeicao()).contains("não encontrado na tabela de referência \"situacao_imovel\"");
    }

    @Test
    @DisplayName("Deve rejeitar quando a tabela de referência estiver vazia no banco")
    void deveRejeitarTabelaVazia() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.bioma WHERE ativo = 1"))
                .thenReturn(List.of());

        ContextoValidacaoCampo ctx = contextoComTabela("bioma", "bioma");
        ResultadoValidacao resultado = validador.validar("CERRADO", ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("VALOR_FORA_DO_DOMINIO");
    }

    // -------------------------------------------------------------------------
    // Casos de borda (Nulos, brancos, SQL injection)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve retornar sucesso sem erro para valor nulo (responsabilidade do SanitizadorEntrada)")
    void deveIgnorarValorNulo() {
        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");
        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("Deve retornar sucesso sem erro para string vazia ou espaços")
    void deveIgnorarStringVazia(String valor) {
        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");
        ResultadoValidacao resultado = validador.validar(valor, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "situacao; DROP TABLE usuarios;",
            "situacao' OR 1=1--",
            "../situacao",
            "tabela com espacos"
    })
    @DisplayName("Deve rejeitar nomes de tabela com caracteres ilegais para proteção contra SQL Injection")
    void deveRejeitarSqlInjectionNomeTabela(String tabelaInvalida) {
        ContextoValidacaoCampo ctx = contextoComTabela("coluna", tabelaInvalida);

        assertThatThrownBy(() -> validador.validar("VALOR", ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido ou suspeito");
    }

    // -------------------------------------------------------------------------
    // Fallback para tabelas sem coluna 'descricao'
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve usar fallback query de 'codigo' caso a tabela não possua 'descricao'")
    void deveUsarFallbackApenasCodigo() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.tabela_simples WHERE ativo = 1"))
                .thenThrow(new DataRetrievalFailureException("Coluna 'descricao' inexistente"));
        when(jdbcTemplate.queryForList("SELECT codigo FROM SILVER.tabela_simples WHERE ativo = 1", String.class))
                .thenReturn(List.of("COD1", "COD2"));

        ContextoValidacaoCampo ctx = contextoComTabela("campo", "tabela_simples");
        ResultadoValidacao resultado = validador.validar("cod1", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("COD1");
    }

    // -------------------------------------------------------------------------
    // Cache e Ação do Analista (Revalidação pós-aprovação)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve reaproveitar o cache e não consultar o banco novamente para a mesma tabela")
    void deveUsarCacheEmChamadasSubsequentes() {
        configurarMockSituacaoImovel();

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");

        // 1ª chamada: consulta o banco
        validador.validar("AT", ctx);
        assertThat(validador.isEmCache("situacao_imovel")).isTrue();
        assertThat(validador.getQuantidadeTabelasEmCache()).isEqualTo(1);

        // 2ª e 3ª chamadas: devem bater apenas no cache
        validador.validar("PE", ctx);
        validador.validar("CA", ctx);

        verify(jdbcTemplate, times(1)).queryForList("SELECT codigo, descricao FROM SILVER.situacao_imovel WHERE ativo = 1");
    }

    @Test
    @DisplayName("Ação do Analista: deve invalidar cache e recarregar novos valores aprovados")
    void deveRevalidarAposAprovacaoDoAnalista() {
        String sql = "SELECT codigo, descricao FROM SILVER.situacao_imovel WHERE ativo = 1";

        // Estado 1: Domínio inicial sem o novo código "REG"
        when(jdbcTemplate.queryForList(sql)).thenReturn(List.of(
                Map.of("CODIGO", "AT", "DESCRICAO", "Ativo")
        ));

        ContextoValidacaoCampo ctx = contextoComTabela("situacao", "situacao_imovel");

        // Validação inicial rejeita "REG"
        ResultadoValidacao inicial = validador.validar("REG", ctx);
        assertThat(inicial.isValido()).isFalse();
        assertThat(inicial.tipoErro()).isEqualTo("VALOR_FORA_DO_DOMINIO");

        // Simula Ação do Analista: novo valor "REG" é aprovado e inserido na tabela Silver
        when(jdbcTemplate.queryForList(sql)).thenReturn(List.of(
                Map.of("CODIGO", "AT", "DESCRICAO", "Ativo"),
                Map.of("CODIGO", "REG", "DESCRICAO", "Regularizado")
        ));

        // Analista ou engine dispara a invalidação do cache da tabela
        validador.invalidarCache("situacao_imovel");
        assertThat(validador.isEmCache("situacao_imovel")).isFalse();

        // Revalidação: a engine recarrega os dados atualizados e aprova com sucesso
        ResultadoValidacao revalidacao = validador.validar("reg", ctx);
        assertThat(revalidacao.isValido()).isTrue();
        assertThat(revalidacao.valorTransformado()).isEqualTo("REG");

        // O banco foi consultado 2 vezes (inicial + pós-invalidação)
        verify(jdbcTemplate, times(2)).queryForList(sql);
    }

    @Test
    @DisplayName("invalidarTodoCache deve limpar todas as tabelas em memória")
    void deveLimparTodoCache() {
        configurarMockSituacaoImovel();
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.bioma WHERE ativo = 1"))
                .thenReturn(List.of(Map.of("CODIGO", "MATA_ATLANTICA", "DESCRICAO", "Mata Atlantica")));

        validador.validar("AT", contextoComTabela("situacao", "situacao_imovel"));
        validador.validar("Mata Atlantica", contextoComTabela("bioma", "bioma"));

        assertThat(validador.getQuantidadeTabelasEmCache()).isEqualTo(2);

        validador.invalidarTodoCache();
        assertThat(validador.getQuantidadeTabelasEmCache()).isEqualTo(0);
        assertThat(validador.isEmCache("situacao_imovel")).isFalse();
        assertThat(validador.isEmCache("bioma")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Tolerância a Acentos, Separadores e Sub-estados Oficiais (CAR / SICAR)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar variações com acento da condicao_imovel (ex.: 'Aguardando análise' -> AGUARDANDO_ANALISE)")
    void deveAprovarCondicaoComAcento() {
        configurarMockCondicaoImovel();
        ContextoValidacaoCampo ctx = contextoComTabela("condicao", "condicao_imovel");

        ResultadoValidacao res1 = validador.validar("Aguardando análise", ctx);
        assertThat(res1.isValido()).isTrue();
        assertThat(res1.valorTransformado()).isEqualTo("AGUARDANDO_ANALISE");

        ResultadoValidacao res2 = validador.validar("Em análise", ctx);
        assertThat(res2.isValido()).isTrue();
        assertThat(res2.valorTransformado()).isEqualTo("EM_ANALISE");

        ResultadoValidacao res3 = validador.validar("Cancelado por decisão administrativa", ctx);
        assertThat(res3.isValido()).isTrue();
        assertThat(res3.valorTransformado()).isEqualTo("CANCELADO_ADM");
    }

    @Test
    @DisplayName("Deve aprovar descrições oficiais do SICAR sob a Lei 12.651/2012 mapeando para códigos canônicos")
    void deveAprovarDescricoesOficiaisSicar() {
        configurarMockCondicaoImovel();
        ContextoValidacaoCampo ctx = contextoComTabela("condicao", "condicao_imovel");

        // 1. Conformidade sem pendências
        ResultadoValidacao r1 = validador.validar("Analisado, em conformidade com a Lei nº 12.651/2012", ctx);
        assertThat(r1.isValido()).isTrue();
        assertThat(r1.valorTransformado()).isEqualTo("ANALISADO_SEM_PENDENCIAS");

        // 2. Conformidade com ativos ambientais
        ResultadoValidacao r2 = validador.validar("Analisado, em conformidade com a Lei nº 12.651/2012, com ativos ambientais", ctx);
        assertThat(r2.isValido()).isTrue();
        assertThat(r2.valorTransformado()).isEqualTo("ANALISADO_SEM_PENDENCIAS");

        // 3. Regularização ambiental (com pendências)
        ResultadoValidacao r3 = validador.validar("Analisado, aguardando regularização ambiental (Lei nº 12.651/2012)", ctx);
        assertThat(r3.isValido()).isTrue();
        assertThat(r3.valorTransformado()).isEqualTo("ANALISADO_COM_PENDENCIAS");

        // 4. Aguardando atendimento a notificação
        ResultadoValidacao r4 = validador.validar("Analisado, aguardando atendimento a notificação", ctx);
        assertThat(r4.isValido()).isTrue();
        assertThat(r4.valorTransformado()).isEqualTo("ANALISADO_COM_PENDENCIAS");

        // 5. Aguardando análise após revisão ou atendimento de notificação
        ResultadoValidacao r5 = validador.validar("Aguardando análise, após revisão ou atendimento da notificação", ctx);
        assertThat(r5.isValido()).isTrue();
        assertThat(r5.valorTransformado()).isEqualTo("AGUARDANDO_ANALISE");
    }

    @Test
    @DisplayName("Deve resolver tipo_imovel com acentos (ex.: 'Imóvel Rural' -> 'IRU')")
    void deveResolverTipoImovelComAcento() {
        configurarMockTipoImovel();
        ContextoValidacaoCampo ctx = contextoComTabela("tipo_imovel", "tipo_imovel");

        ResultadoValidacao r1 = validador.validar("Imóvel Rural", ctx);
        assertThat(r1.isValido()).isTrue();
        assertThat(r1.valorTransformado()).isEqualTo("IRU");

        ResultadoValidacao r2 = validador.validar("Assentamento de Reforma Agrária", ctx);
        assertThat(r2.isValido()).isTrue();
        assertThat(r2.valorTransformado()).isEqualTo("AST");

        ResultadoValidacao r3 = validador.validar("Território de Povos e Comunidades Tradicionais", ctx);
        assertThat(r3.isValido()).isTrue();
        assertThat(r3.valorTransformado()).isEqualTo("PCT");
    }

    @Test
    @DisplayName("Deve validar domínio de município (código IBGE, prefixo PR- ou nome) e rejeitar quando inexistente")
    void deveValidarDominioMunicipio() {
        when(jdbcTemplate.queryForList("SELECT CAST(cod_ibge AS VARCHAR2(20)) AS codigo, nome AS descricao FROM SILVER.municipio"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "4106902", "DESCRICAO", "Curitiba"),
                        Map.of("CODIGO", "4108304", "DESCRICAO", "Foz do Iguaçu")
                ));

        ContextoValidacaoCampo ctx = contextoComTabela("municipio", "municipio");

        // 1. Por código IBGE
        ResultadoValidacao r1 = validador.validar("4106902", ctx);
        assertThat(r1.isValido()).isTrue();
        assertThat(r1.valorTransformado()).isEqualTo("4106902");

        // 2. Com prefixo PR-
        ResultadoValidacao r2 = validador.validar("PR-4106902", ctx);
        assertThat(r2.isValido()).isTrue();
        assertThat(r2.valorTransformado()).isEqualTo("4106902");

        // 3. Por nome com acento
        ResultadoValidacao r3 = validador.validar("Foz do Iguaçu", ctx);
        assertThat(r3.isValido()).isTrue();
        assertThat(r3.valorTransformado()).isEqualTo("4108304");

        // 4. Inexistente -> Rejeição
        ResultadoValidacao r4 = validador.validar("9999999", ctx);
        assertThat(r4.isValido()).isFalse();
        assertThat(r4.tipoErro()).isEqualTo("VALOR_FORA_DO_DOMINIO");
    }

    @Test
    @DisplayName("Deve validar unidade_federativa e normalizar aliases (uf -> unidade_federativa)")
    void deveValidarUnidadeFederativaEAlias() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao, CAST(cod_ibge AS VARCHAR2(10)) AS cod_ibge FROM SILVER.unidade_federativa WHERE ativo = 1"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "PR", "DESCRICAO", "Parana", "COD_IBGE", "41"),
                        Map.of("CODIGO", "SP", "DESCRICAO", "Sao Paulo", "COD_IBGE", "35")
                ));

        ContextoValidacaoCampo ctx = contextoComTabela("uf", "uf");

        ResultadoValidacao r1 = validador.validar("PR", ctx);
        assertThat(r1.isValido()).isTrue();
        assertThat(r1.valorTransformado()).isEqualTo("PR");

        ResultadoValidacao r2 = validador.validar("Paraná", ctx);
        assertThat(r2.isValido()).isTrue();
        assertThat(r2.valorTransformado()).isEqualTo("PR");

        ResultadoValidacao r3 = validador.validar("41", ctx);
        assertThat(r3.isValido()).isTrue();
        assertThat(r3.valorTransformado()).isEqualTo("PR");

        ResultadoValidacao rInvalido = validador.validar("XX", ctx);
        assertThat(rInvalido.isValido()).isFalse();
        assertThat(rInvalido.tipoErro()).isEqualTo("VALOR_FORA_DO_DOMINIO");
    }

    @Test
    @DisplayName("Deve validar auto_infracao existente e rejeitar inexistente")
    void deveValidarAutoInfracao() {
        when(jdbcTemplate.queryForList("SELECT numero_auto AS codigo, numero_auto AS descricao FROM SILVER.auto_infracao"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "AUTO-12345", "DESCRICAO", "AUTO-12345")
                ));

        ContextoValidacaoCampo ctx = contextoComTabela("num_auto", "auto_infracao");

        ResultadoValidacao r1 = validador.validar("AUTO-12345", ctx);
        assertThat(r1.isValido()).isTrue();
        assertThat(r1.valorTransformado()).isEqualTo("AUTO-12345");

        ResultadoValidacao r2 = validador.validar("AUTO-99999", ctx);
        assertThat(r2.isValido()).isFalse();
        assertThat(r2.tipoErro()).isEqualTo("VALOR_FORA_DO_DOMINIO");
    }

    @Test
    @DisplayName("Deve normalizar aliases de tabelas classes_prodes, tipo_area_embrago e bioma_predominante")
    void deveNormalizarAliasesDiversos() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.classe_prodes WHERE ativo = 1"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "DESMATAMENTO", "DESCRICAO", "Desmatamento (corte raso)")
                ));

        ContextoValidacaoCampo ctxProdes = contextoComTabela("classe", "classes_prodes");
        ResultadoValidacao rProdes = validador.validar("Desmatamento", ctxProdes);
        assertThat(rProdes.isValido()).isTrue();
        assertThat(rProdes.valorTransformado()).isEqualTo("DESMATAMENTO");

        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.tipo_area_embargo WHERE ativo = 1"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "QUEIMADA", "DESCRICAO", "Queimada / incendio")
                ));

        ContextoValidacaoCampo ctxEmbargo = contextoComTabela("tipo_area", "tipo_area_embrago");
        ResultadoValidacao rEmbargo = validador.validar("Queimada", ctxEmbargo);
        assertThat(rEmbargo.isValido()).isTrue();
        assertThat(rEmbargo.valorTransformado()).isEqualTo("QUEIMADA");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void configurarMockSituacaoImovel() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.situacao_imovel WHERE ativo = 1"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "AT", "DESCRICAO", "Ativo"),
                        Map.of("CODIGO", "PE", "DESCRICAO", "Pendente"),
                        Map.of("CODIGO", "SU", "DESCRICAO", "Suspenso"),
                        Map.of("CODIGO", "CA", "DESCRICAO", "Cancelado")
                ));
    }

    private void configurarMockCondicaoImovel() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.condicao_imovel WHERE ativo = 1"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "AGUARDANDO_ANALISE", "DESCRICAO", "Aguardando analise"),
                        Map.of("CODIGO", "EM_ANALISE", "DESCRICAO", "Em analise"),
                        Map.of("CODIGO", "ANALISADO_SEM_PENDENCIAS", "DESCRICAO", "Analisado sem pendencias"),
                        Map.of("CODIGO", "ANALISADO_COM_PENDENCIAS", "DESCRICAO", "Analisado com pendencias"),
                        Map.of("CODIGO", "CANCELADO_ADM", "DESCRICAO", "Cancelado por decisao administrativa"),
                        Map.of("CODIGO", "CANCELADO_JUDICIAL", "DESCRICAO", "Cancelado por decisao judicial")
                ));
    }

    private void configurarMockTipoImovel() {
        when(jdbcTemplate.queryForList("SELECT codigo, descricao FROM SILVER.tipo_imovel WHERE ativo = 1"))
                .thenReturn(List.of(
                        Map.of("CODIGO", "IRU", "DESCRICAO", "Imovel Rural"),
                        Map.of("CODIGO", "AST", "DESCRICAO", "Assentamento de Reforma Agraria"),
                        Map.of("CODIGO", "PCT", "DESCRICAO", "Territorio de Povos e Comunidades Tradicionais")
                ));
    }

    private ContextoValidacaoCampo contextoComTabela(String coluna, String tabela) {
        return ContextoValidacaoCampo.builder()
                .colunaOrigem(coluna)
                .tabelaReferencia(tabela)
                .build();
    }
}
