package br.gov.sp.cps.datagis.validacao;

import org.springframework.stereotype.Component;

/**
 * 2.2. Validador de CPF / CNPJ.
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Remover caracteres não numéricos ({@code .}, {@code -}, {@code /}, espaços);</li>
 *   <li>Validar a quantidade de dígitos (11 para CPF, 14 para CNPJ);</li>
 *   <li>Rejeitar sequências conhecidas de dígitos repetidos (ex.: {@code 111.111.111-11});</li>
 *   <li>Aplicar o algoritmo de Dígito Verificador (Módulo 11) oficial para CPF e CNPJ;</li>
 *   <li>Retornar erro {@code CPF_CNPJ_INVALIDO} caso qualquer uma das checagens falhe.</li>
 * </ul>
 *
 * <p>Valor null é ignorado (responsabilidade do {@link SanitizadorEntrada}).
 * Em caso de sucesso devolve apenas os dígitos normalizados para persistência limpa.
 */
@Component
public class ValidadorCpfCnpj implements ValidadorCampo {

    public static final String ERRO_CPF_CNPJ = "CPF_CNPJ_INVALIDO";

    private static final int[] PESOS_CNPJ_DIGITO1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS_CNPJ_DIGITO2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        if (contexto == null) {
            return false;
        }
        return contexto.validarCpfCnpj()
                || "cpf_cnpj".equalsIgnoreCase(contexto.tipoDado())
                || "cpf".equalsIgnoreCase(contexto.tipoDado())
                || "cnpj".equalsIgnoreCase(contexto.tipoDado());
    }

    @Override
    public ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto) {
        if (valor == null) {
            // Valor nulo é responsabilidade do SanitizadorEntrada (se obrigatorio)
            return ResultadoValidacao.sucesso(null);
        }

        String valorOriginal = valor.toString();
        String digitos = valorOriginal.replaceAll("\\D", "");

        if (digitos.isEmpty()) {
            return ResultadoValidacao.sucesso(null);
        }

        // Fontes que mandam CPF/CNPJ como NUMERO (nao string) no JSON/GeoJSON
        // perdem zero(s) a esquerda no parsing - achado ao vivo nos Embargos
        // IBAMA/ICMBio: "4579237927" chegando com 10 digitos (falta 1 zero),
        // "365707961" com 9 (faltam 2). Repoe o(s) zero(s) perdido(s) antes de
        // validar - o digito verificador (modulo 11) abaixo continua sendo a
        // prova real: se o valor com zero reposto nao bater o checksum, segue
        // invalido do mesmo jeito, nao aceita qualquer coisa.
        if (digitos.length() == 9 || digitos.length() == 10) {
            digitos = "0".repeat(11 - digitos.length()) + digitos;
        } else if (digitos.length() == 12 || digitos.length() == 13) {
            digitos = "0".repeat(14 - digitos.length()) + digitos;
        }

        boolean valido = false;
        if (digitos.length() == 11) {
            valido = isCpfValido(digitos);
        } else if (digitos.length() == 14) {
            valido = isCnpjValido(digitos);
        }

        if (!valido) {
            String nomeCampo = contexto != null && contexto.colunaOrigem() != null
                    ? contexto.colunaOrigem()
                    : "cpf_cnpj";
            return ResultadoValidacao.erro(
                    ERRO_CPF_CNPJ,
                    nomeCampo,
                    valorOriginal,
                    "Número de CPF/CNPJ \"" + valorOriginal + "\" inválido ou com dígitos verificadores incorretos"
            );
        }

        // Retorna apenas os dígitos normalizados para persistência limpa
        return ResultadoValidacao.sucesso(digitos);
    }

    /**
     * Valida um CPF de 11 dígitos numéricos aplicando módulo 11.
     */
    public static boolean isCpfValido(String cpf) {
        if (cpf == null || cpf.length() != 11) {
            return false;
        }
        // Rejeita sequências com todos os dígitos iguais (00000000000, 11111111111...)
        if (cpf.matches("(\\d)\\1{10}")) {
            return false;
        }

        int soma1 = 0;
        for (int i = 0; i < 9; i++) {
            soma1 += (cpf.charAt(i) - '0') * (10 - i);
        }
        int resto1 = soma1 % 11;
        int digito1 = resto1 < 2 ? 0 : 11 - resto1;
        if ((cpf.charAt(9) - '0') != digito1) {
            return false;
        }

        int soma2 = 0;
        for (int i = 0; i < 10; i++) {
            soma2 += (cpf.charAt(i) - '0') * (11 - i);
        }
        int resto2 = soma2 % 11;
        int digito2 = resto2 < 2 ? 0 : 11 - resto2;
        return (cpf.charAt(10) - '0') == digito2;
    }

    /**
     * Valida um CNPJ de 14 dígitos numéricos aplicando módulo 11.
     */
    public static boolean isCnpjValido(String cnpj) {
        if (cnpj == null || cnpj.length() != 14) {
            return false;
        }
        // Rejeita sequências com todos os dígitos iguais (00000000000000, 11111111111111...)
        if (cnpj.matches("(\\d)\\1{13}")) {
            return false;
        }

        int soma1 = 0;
        for (int i = 0; i < 12; i++) {
            soma1 += (cnpj.charAt(i) - '0') * PESOS_CNPJ_DIGITO1[i];
        }
        int resto1 = soma1 % 11;
        int digito1 = resto1 < 2 ? 0 : 11 - resto1;
        if ((cnpj.charAt(12) - '0') != digito1) {
            return false;
        }

        int soma2 = 0;
        for (int i = 0; i < 13; i++) {
            soma2 += (cnpj.charAt(i) - '0') * PESOS_CNPJ_DIGITO2[i];
        }
        int resto2 = soma2 % 11;
        int digito2 = resto2 < 2 ? 0 : 11 - resto2;
        return (cnpj.charAt(13) - '0') == digito2;
    }
}
