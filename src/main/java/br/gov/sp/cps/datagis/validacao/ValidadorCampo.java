package br.gov.sp.cps.datagis.validacao;

/**
 * Contrato base para validadores de campo na esteira de triagem Bronze -> Silver / Quarentena.
 *
 * <p>Permite encadear regras independentes (Sanitizacao, CPF/CNPJ, Faixa Minima, Dominio, etc.)
 * seguindo os padroes Strategy e Chain of Responsibility.
 */
public interface ValidadorCampo {

    /**
     * Indica se este validador deve ser executado para o campo informado.
     */
    boolean suporta(ContextoValidacaoCampo contexto);

    /**
     * Valida e/ou sanitiza o valor recebido com base no contexto do mapeamento.
     *
     * @param valor valor recebido do payload (geralmente String no Bronze)
     * @param contexto metadados e regras da coluna configurados em {@code mapeamento_coluna}
     * @return resultado da validacao com o valor tratado ou os detalhes do erro para a Quarentena
     */
    ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto);
}
