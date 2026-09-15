package br.gov.sp.cps.datagis.quarentena;

/**
 * Representação unificada de um registro retido na Quarentena.
 */
public record ItemQuarentenaDto(
        Long id,
        String entidade,
        String chaveIdentificacao,
        Long fonteDadoId,
        String fonteDadoNome,
        Long loteIngestaoId,
        Long registroBrutoId,
        String tipoErroCodigo,
        String tipoErroDescricao,
        String campoProblema,
        String valorRecebido,
        String motivoRejeicao,
        String statusAnaliseCodigo,
        String statusAnaliseDescricao,
        String dataQuarentena,
        String dataResolucao,
        String payloadOriginal
) {}
