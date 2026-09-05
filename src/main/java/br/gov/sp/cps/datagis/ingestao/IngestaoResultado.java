package br.gov.sp.cps.datagis.ingestao;

/** Resposta do endpoint de ingestao. */
public record IngestaoResultado(Long loteIngestaoId, Integer quantidadeRegistros, String status) {
}
