package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidadorCpfCnpjTest {

    private final ValidadorCpfCnpj validador = new ValidadorCpfCnpj();

    // -------------------------------------------------------------------------
    // suporta()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar coluna com flag validarCpfCnpj = true")
    void deveSuportarPelaFlag() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("cpf_proprietario")
                .validarCpfCnpj(true)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cpf_cnpj", "CPF_CNPJ", "cpf", "CPF", "cnpj", "CNPJ"})
    @DisplayName("Deve suportar quando tipoDado indicar CPF ou CNPJ (case-insensitive)")
    void deveSuportarPeloTipoDado(String tipo) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("documento")
                .tipoDado(tipo)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @Test
    @DisplayName("Não deve suportar quando flag e tipoDado não indicarem CPF/CNPJ")
    void naoDeveSuportarSemFlagNemTipo() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("area_ha")
                .tipoDado("numero")
                .validarCpfCnpj(false)
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @Test
    @DisplayName("Não deve suportar contexto nulo")
    void naoDeveSuportarContextoNulo() {
        assertThat(validador.suporta(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // validar() — CPF
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "529.982.247-25",    // formatado com máscara
            "52998224725",       // apenas dígitos
            "  529.982.247-25 " // com espaços nas pontas (replaceAll(\D) remove tudo)
    })
    @DisplayName("Deve aprovar CPF válido e retornar apenas os dígitos normalizados")
    void deveAprovarCpfValido(String cpf) {
        ContextoValidacaoCampo ctx = contextoComFlag("cpf_proprietario");

        ResultadoValidacao resultado = validador.validar(cpf, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("52998224725");
        assertThat(resultado.tipoErro()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "000.000.000-00",  // sequência trivial
            "111.111.111-11",  // sequência trivial
            "222.222.222-22",  // sequência trivial
            "999.999.999-99",  // sequência trivial
            "123.456.789-00",  // dígitos em posição errada
            "529.982.247-26"   // DV errado (correto seria -25)
    })
    @DisplayName("Deve rejeitar CPF inválido com erro CPF_CNPJ_INVALIDO")
    void deveRejeitarCpfInvalido(String cpf) {
        ContextoValidacaoCampo ctx = contextoComFlag("cpf_proprietario");

        ResultadoValidacao resultado = validador.validar(cpf, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("CPF_CNPJ_INVALIDO");
        assertThat(resultado.campoProblema()).isEqualTo("cpf_proprietario");
        assertThat(resultado.motivoRejeicao()).contains("inválido ou com dígitos verificadores incorretos");
    }

    @Test
    @DisplayName("Deve restaurar zero(s) à esquerda perdido(s) quando a fonte era JSON numérico, antes de validar o CPF")
    void deveRestaurarZeroAEsquerdaDoCpf() {
        // CPF real "00123456797" chega como "123456797" (9 dígitos) quando a fonte
        // tipa a coluna como número em vez de texto (achado ao vivo nos Embargos
        // IBAMA/ICMBio, V072-era LIZARDS-51).
        ContextoValidacaoCampo ctx = contextoComFlag("cpf_proprietario");

        ResultadoValidacao resultado = validador.validar("123456797", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("00123456797");
    }

    // -------------------------------------------------------------------------
    // validar() — CNPJ
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "11.222.333/0001-81",   // formatado com máscara
            "11222333000181",       // apenas dígitos
            "  11.222.333/0001-81 " // com espaços nas pontas
    })
    @DisplayName("Deve aprovar CNPJ válido e retornar apenas os dígitos normalizados")
    void deveAprovarCnpjValido(String cnpj) {
        ContextoValidacaoCampo ctx = contextoComFlag("cnpj_empresa");

        ResultadoValidacao resultado = validador.validar(cnpj, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("11222333000181");
        assertThat(resultado.tipoErro()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00.000.000/0000-00",  // sequência trivial
            "11.111.111/1111-11",  // sequência trivial
            "11.222.333/0001-82",  // DV errado (correto seria -81)
            "11.222.333/0001-9"    // dígitos insuficientes (13 após strip)
    })
    @DisplayName("Deve rejeitar CNPJ inválido com erro CPF_CNPJ_INVALIDO")
    void deveRejeitarCnpjInvalido(String cnpj) {
        ContextoValidacaoCampo ctx = contextoComFlag("cnpj_empresa");

        ResultadoValidacao resultado = validador.validar(cnpj, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("CPF_CNPJ_INVALIDO");
        assertThat(resultado.campoProblema()).isEqualTo("cnpj_empresa");
    }

    @Test
    @DisplayName("Deve restaurar zero(s) à esquerda perdido(s) quando a fonte era JSON numérico, antes de validar o CNPJ")
    void deveRestaurarZeroAEsquerdaDoCnpj() {
        // CNPJ real "00123456000149" chega como "123456000149" (12 dígitos) pelo
        // mesmo motivo do caso do CPF acima.
        ContextoValidacaoCampo ctx = contextoComFlag("cnpj_empresa");

        ResultadoValidacao resultado = validador.validar("123456000149", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isEqualTo("00123456000149");
    }

    // -------------------------------------------------------------------------
    // validar() — casos de borda
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve retornar sucesso sem erro para valor null (responsabilidade do SanitizadorEntrada)")
    void deveIgnorarValorNull() {
        ContextoValidacaoCampo ctx = contextoComFlag("cpf_proprietario");

        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve retornar sucesso sem erro para string vazia (já tratada pelo SanitizadorEntrada)")
    void deveIgnorarStringVazia() {
        ContextoValidacaoCampo ctx = contextoComFlag("cpf_proprietario");

        ResultadoValidacao resultado = validador.validar("", ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @Test
    @DisplayName("Deve rejeitar documento com quantidade de dígitos diferente de 11 ou 14")
    void deveRejeitarQuantidadeDigitosInvalida() {
        ContextoValidacaoCampo ctx = contextoComFlag("documento");

        ResultadoValidacao resultado = validador.validar("123456789", ctx); // 9 dígitos

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("CPF_CNPJ_INVALIDO");
    }

    @Test
    @DisplayName("Deve usar 'cpf_cnpj' como fallback de campoProblema quando contexto for null")
    void deveUsarFallbackNomeCampoComContextoNulo() {
        ResultadoValidacao resultado = validador.validar("000.000.000-00", null);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.campoProblema()).isEqualTo("cpf_cnpj");
    }

    // -------------------------------------------------------------------------
    // isCpfValido / isCnpjValido — helpers estáticos públicos
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isCpfValido deve retornar false para null ou tamanho diferente de 11")
    void isCpfValidoDeveRetornarFalseParaNullOuTamanhoErrado() {
        assertThat(ValidadorCpfCnpj.isCpfValido(null)).isFalse();
        assertThat(ValidadorCpfCnpj.isCpfValido("1234567890")).isFalse();   // 10 dígitos
        assertThat(ValidadorCpfCnpj.isCpfValido("123456789012")).isFalse(); // 12 dígitos
    }

    @Test
    @DisplayName("isCnpjValido deve retornar false para null ou tamanho diferente de 14")
    void isCnpjValidoDeveRetornarFalseParaNullOuTamanhoErrado() {
        assertThat(ValidadorCpfCnpj.isCnpjValido(null)).isFalse();
        assertThat(ValidadorCpfCnpj.isCnpjValido("1234567890123")).isFalse();   // 13 dígitos
        assertThat(ValidadorCpfCnpj.isCnpjValido("123456789012345")).isFalse(); // 15 dígitos
    }

    // -------------------------------------------------------------------------
    // helpers de teste
    // -------------------------------------------------------------------------

    private ContextoValidacaoCampo contextoComFlag(String coluna) {
        return ContextoValidacaoCampo.builder()
                .colunaOrigem(coluna)
                .validarCpfCnpj(true)
                .build();
    }
}
