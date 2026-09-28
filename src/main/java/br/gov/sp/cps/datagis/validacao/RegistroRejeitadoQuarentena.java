package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;

/**
 * Representa um registro que falhou em uma ou mais regras de validação
 * e deve ser retido na Quarentena com o bloco de auditoria correspondente.
 */
public record RegistroRejeitadoQuarentena(
        RegistroBruto registroBruto,
        String tipoErro,
        String campoProblema,
        String valorRecebido,
        String motivoRejeicao
) {}
