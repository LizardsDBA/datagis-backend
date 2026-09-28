package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SanitizadorEntradaTest {

    private final SanitizadorEntrada sanitizador = new SanitizadorEntrada();

    @Test
    @DisplayName("Deve converter valor nulo nativo para null")
    void deveConverterNuloParaNull() {
        assertThat(sanitizador.sanitizar(null)).isNull();
        assertThat(sanitizador.sanitizarObjeto(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    @DisplayName("Deve converter strings vazias ou em branco para null")
    void deveConverterVazioOuEspacosParaNull(String valor) {
        assertThat(sanitizador.sanitizar(valor)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "null", "Null", "  NULL  ", "N/A", "n/a", "N/a", "  N/A  ", "-", "  -  "})
    @DisplayName("Deve converter strings falsas nulas conhecidas para null")
    void deveConverterFalsosNulosConhecidosParaNull(String falsoNulo) {
        assertThat(sanitizador.sanitizar(falsoNulo)).isNull();
        assertThat(sanitizador.sanitizarObjeto(falsoNulo)).isNull();
    }

    @Test
    @DisplayName("Deve preservar valor legítimo e remover espaços nas pontas")
    void devePreservarValorLegitimoComTrim() {
        assertThat(sanitizador.sanitizar("  Curitiba  ")).isEqualTo("Curitiba");
        assertThat(sanitizador.sanitizar("PR-4106902-12345")).isEqualTo("PR-4106902-12345");
        assertThat(sanitizador.sanitizarObjeto(42)).isEqualTo(42);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "null", "N/A", "  ", ""})
    @DisplayName("Deve rejeitar quando campo for obrigatório e valor for nulo ou falso nulo")
    void deveRejeitarCampoObrigatorioComFalsoNulo(String valorInvalido) {
        ContextoValidacaoCampo contexto = ContextoValidacaoCampo.simples("cod_imovel", "codigo_imovel", true);

        ResultadoValidacao resultado = sanitizador.validar(valorInvalido, contexto);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(resultado.campoProblema()).isEqualTo("cod_imovel");
        assertThat(resultado.valorRecebido()).isEqualTo(valorInvalido);
        assertThat(resultado.motivoRejeicao())
                .contains("Campo obrigatório \"cod_imovel\" ausente ou preenchido com valor nulo/em branco");
    }

    @Test
    @DisplayName("Deve rejeitar quando campo for obrigatório e valor for null real")
    void deveRejeitarCampoObrigatorioComNullReal() {
        ContextoValidacaoCampo contexto = ContextoValidacaoCampo.simples("nome_proprietario", "nome", true);

        ResultadoValidacao resultado = sanitizador.validar(null, contexto);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(resultado.campoProblema()).isEqualTo("nome_proprietario");
        assertThat(resultado.valorRecebido()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar quando campo for opcional mesmo se o valor for falso nulo ou null real")
    void deveAprovarCampoOpcionalComFalsoNulo() {
        ContextoValidacaoCampo contexto = ContextoValidacaoCampo.simples("complemento", "complemento", false);

        ResultadoValidacao resultadoComFalsoNulo = sanitizador.validar("N/A", contexto);
        assertThat(resultadoComFalsoNulo.isValido()).isTrue();
        assertThat(resultadoComFalsoNulo.valorTransformado()).isNull();

        ResultadoValidacao resultadoComNullReal = sanitizador.validar(null, contexto);
        assertThat(resultadoComNullReal.isValido()).isTrue();
        assertThat(resultadoComNullReal.valorTransformado()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar campo preenchido com valor legítimo")
    void deveAprovarCampoPreenchido() {
        ContextoValidacaoCampo contexto = ContextoValidacaoCampo.simples("municipio", "municipio", true);

        ResultadoValidacao resultado = sanitizador.validar("  Maringá  ", contexto);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("Maringá");
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve indicar suporte a todos os campos")
    void deveSuportarTodosOsCampos() {
        ContextoValidacaoCampo contexto = ContextoValidacaoCampo.simples("qualquer", "qualquer", false);
        assertThat(sanitizador.suporta(contexto)).isTrue();
    }
}
