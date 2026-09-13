package br.gov.sp.cps.datagis.validacao;

/**
 * Representa o resultado da validação de um campo ou registro.
 *
 * <p>Em caso de sucesso, carrega o valor transformado/sanitizado.
 * Em caso de falha, carrega as informações necessárias para registrar
 * a inconsistência no bloco de auditoria da Quarentena (tabelas {@code q_*}).
 */
public record ResultadoValidacao(
        boolean valido,
        Object valorTransformado,
        String tipoErro,
        String campoProblema,
        String valorRecebido,
        String motivoRejeicao) {

    public static ResultadoValidacao sucesso(Object valorTransformado) {
        return new ResultadoValidacao(true, valorTransformado, null, null, null, null);
    }

    public static ResultadoValidacao erro(
            String tipoErro,
            String campoProblema,
            String valorRecebido,
            String motivoRejeicao) {
        return new ResultadoValidacao(false, null, tipoErro, campoProblema, valorRecebido, motivoRejeicao);
    }

    public boolean isValido() {
        return valido;
    }
}
