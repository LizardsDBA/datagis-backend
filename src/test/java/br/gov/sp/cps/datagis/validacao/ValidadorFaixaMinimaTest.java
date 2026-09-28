package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidadorFaixaMinimaTest {

    private final ValidadorFaixaMinima validador = new ValidadorFaixaMinima();

    // -------------------------------------------------------------------------
    // suporta()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar quando valorMinimo estiver definido no contexto")
    void deveSuportarComValorMinimo() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area_ha")
                .valorMinimo(0.0)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @Test
    @DisplayName("Não deve suportar quando valorMinimo for nulo")
    void naoDeveSuportarSemValorMinimo() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area_ha")
                .valorMinimo(null)
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @Test
    @DisplayName("Não deve suportar contexto nulo")
    void naoDeveSuportarContextoNulo() {
        assertThat(validador.suporta(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // validar() — Sucesso e conversões
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar número maior que o mínimo")
    void deveAprovarNumeroMaior() {
        ContextoValidacaoCampo ctx = contextoComMinimo("area_ha", 0.0);
        ResultadoValidacao resultado = validador.validar(10.5, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo(10.5);
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar número exatamente igual ao mínimo (limite inclusivo)")
    void deveAprovarNumeroIgualAoMinimo() {
        ContextoValidacaoCampo ctx = contextoComMinimo("modulos_fiscais", 1.0);
        ResultadoValidacao resultado = validador.validar(1.0, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Deve aceitar e converter tipos Number variados (Integer, Long, BigDecimal)")
    void deveAceitarTiposNumberVariados() {
        ContextoValidacaoCampo ctx = contextoComMinimo("area", 5.0);

        assertThat(validador.validar(10, ctx).valorTransformado()).isEqualTo(10.0);
        assertThat(validador.validar(100L, ctx).valorTransformado()).isEqualTo(100.0);
        assertThat(validador.validar(new BigDecimal("25.75"), ctx).valorTransformado()).isEqualTo(25.75);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "15.5",          // ponto decimal
            "15,5",          // vírgula decimal brasileira
            "  20.0  ",      // espaços nas extremidades
            "1.250,50",      // milhar ponto + decimal vírgula
            "1,250.50",      // milhar vírgula + decimal ponto
            "R$ 50,00",      // com símbolo de moeda
            "100"            // inteiro como string
    })
    @DisplayName("Deve converter formatos numéricos textuais com tolerância")
    void deveConverterFormatosTextuais(String valorTexto) {
        ContextoValidacaoCampo ctx = contextoComMinimo("valor_multa", 10.0);
        ResultadoValidacao resultado = validador.validar(valorTexto, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat((Double) resultado.valorTransformado()).isGreaterThanOrEqualTo(10.0);
    }

    // -------------------------------------------------------------------------
    // validar() — Rejeição por limite (VALOR_ABAIXO_DO_MINIMO)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve rejeitar valor menor que o mínimo com erro VALOR_ABAIXO_DO_MINIMO")
    void deveRejeitarAbaixoDoMinimo() {
        ContextoValidacaoCampo ctx = contextoComMinimo("area_ha", 0.1);
        ResultadoValidacao resultado = validador.validar(0.05, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("VALOR_ABAIXO_DO_MINIMO");
        assertThat(resultado.campoProblema()).isEqualTo("area_ha");
        assertThat(resultado.valorRecebido()).isEqualTo("0.05");
        assertThat(resultado.motivoRejeicao()).contains("inferior ao mínimo permitido de 0.1");
    }

    @Test
    @DisplayName("Deve rejeitar valores negativos quando o mínimo for zero")
    void deveRejeitarValoresNegativos() {
        ContextoValidacaoCampo ctx = contextoComMinimo("area_ha", 0.0);
        ResultadoValidacao resultado = validador.validar("-5.2", ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("VALOR_ABAIXO_DO_MINIMO");
    }

    // -------------------------------------------------------------------------
    // validar() — Rejeição por tipo inválido (TIPO_DE_DADO_INVALIDO)
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "oito virgula oito",
            "invalido",
            "abc123",
            "1.2.3.4.5"
    })
    @DisplayName("Deve rejeitar texto não-numérico com erro TIPO_DE_DADO_INVALIDO")
    void deveRejeitarTextoNaoNumerico(String valorInvalido) {
        ContextoValidacaoCampo ctx = contextoComMinimo("area", 0.0);
        ResultadoValidacao resultado = validador.validar(valorInvalido, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("TIPO_DE_DADO_INVALIDO");
        assertThat(resultado.campoProblema()).isEqualTo("area");
        assertThat(resultado.motivoRejeicao()).contains("não é um número válido");
    }

    // -------------------------------------------------------------------------
    // Casos de borda (Nulos, vazios)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve retornar sucesso sem erro para valor nulo (responsabilidade do SanitizadorEntrada)")
    void deveIgnorarValorNulo() {
        ContextoValidacaoCampo ctx = contextoComMinimo("area_ha", 0.0);
        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("Deve retornar sucesso sem erro para string vazia ou espaços")
    void deveIgnorarStringVazia(String valor) {
        ContextoValidacaoCampo ctx = contextoComMinimo("area_ha", 0.0);
        ResultadoValidacao resultado = validador.validar(valor, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ContextoValidacaoCampo contextoComMinimo(String coluna, Double minimo) {
        return ContextoValidacaoCampo.builder()
                .colunaOrigem(coluna)
                .valorMinimo(minimo)
                .build();
    }
}
