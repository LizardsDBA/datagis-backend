package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ValidadorRegressaoTest {

    private final ValidadorRegressao validador = new ValidadorRegressao();

    // -------------------------------------------------------------------------
    // suporta()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar quando impedirRegressao = true")
    void deveSuportarComImpedirRegressao() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area_ha")
                .impedirRegressao(true)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @Test
    @DisplayName("Não deve suportar quando impedirRegressao = false")
    void naoDeveSuportarSemImpedirRegressao() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area_ha")
                .impedirRegressao(false)
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @Test
    @DisplayName("Não deve suportar contexto nulo")
    void naoDeveSuportarContextoNulo() {
        assertThat(validador.suporta(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // validar() — Regressão detectada (REGRESSAO_DE_VALOR)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve rejeitar com REGRESSAO_DE_VALOR quando valor atual for nulo e valor anterior existia")
    void deveRejeitarQuandoValorAtualForNulo() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area")
                .impedirRegressao(true)
                .valorAnterior(22.5)
                .build();

        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("REGRESSAO_DE_VALOR");
        assertThat(resultado.campoProblema()).isEqualTo("area");
        assertThat(resultado.motivoRejeicao()).contains("estava preenchido no snapshot anterior com \"22.5\" e veio nulo/vazio");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "null", "NULL", "n/a", "N/A", "-"})
    @DisplayName("Deve rejeitar quando valor atual for vazio ou falso nulo e valor anterior existia")
    void deveRejeitarQuandoValorAtualForVazioOuFalsoNulo(String valorVazio) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("cpf_proprietario")
                .impedirRegressao(true)
                .valorAnterior("11122233344")
                .build();

        ResultadoValidacao resultado = validador.validar(valorVazio, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("REGRESSAO_DE_VALOR");
        assertThat(resultado.campoProblema()).isEqualTo("cpf_proprietario");
    }

    // -------------------------------------------------------------------------
    // validar() — Sucesso (sem regressão)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar quando ambos valor atual e anterior estiverem preenchidos (atualização legítima)")
    void deveAprovarAtualizacaoLegitima() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area")
                .impedirRegressao(true)
                .valorAnterior(22.5)
                .build();

        ResultadoValidacao resultado = validador.validar(25.0, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo(25.0);
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar quando campo era nulo anteriormente e agora veio preenchido")
    void deveAprovarPreenchimentoNovo() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("numero_art")
                .impedirRegressao(true)
                .valorAnterior(null)
                .build();

        ResultadoValidacao resultado = validador.validar("ART-2026-999", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("ART-2026-999");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "null", "N/A", "-"})
    @DisplayName("Deve aprovar quando ambos eram nulos/vazios (permanência de estado nulo)")
    void deveAprovarQuandoAmbosForemNulos(String valorNulo) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("observacao")
                .impedirRegressao(true)
                .valorAnterior(valorNulo)
                .build();

        ResultadoValidacao resultado = validador.validar(valorNulo, ctx);

        assertThat(resultado.isValido()).isTrue();
    }

    @Test
    @DisplayName("Deve aprovar quando impedirRegressao for false mesmo com perda de valor")
    void deveAprovarQuandoImpedirRegressaoForFalse() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("campo_flexivel")
                .impedirRegressao(false)
                .valorAnterior("VALOR_ANTIGO")
                .build();

        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helper houveRegressao()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("houveRegressao deve retornar true apenas quando atual for vazio e anterior preenchido")
    void testHouveRegressaoHelper() {
        assertThat(validador.houveRegressao(null, "valor")).isTrue();
        assertThat(validador.houveRegressao("", "valor")).isTrue();
        assertThat(validador.houveRegressao("   ", 100)).isTrue();
        assertThat(validador.houveRegressao("novo", "antigo")).isFalse();
        assertThat(validador.houveRegressao("novo", null)).isFalse();
        assertThat(validador.houveRegressao(null, null)).isFalse();
        assertThat(validador.houveRegressao(null, "")).isFalse();
    }
}
