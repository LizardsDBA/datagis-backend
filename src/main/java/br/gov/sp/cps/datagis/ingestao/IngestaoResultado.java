package br.gov.sp.cps.datagis.ingestao;

/** Resposta do endpoint de ingestão (CLAUDE.md §5.2). */
public record IngestaoResultado(Long loteIngestaoId, Integer quantidadeRegistros) {
}
