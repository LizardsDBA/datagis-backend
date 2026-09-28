package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;

/**
 * DTO para exibição e gestão de lotes de ingestão no portal.
 */
public record LoteDto(
        Long id,
        Long fonteDadoId,
        String fonteDadoNome,
        String origemReferencia,
        Integer quantidadeRegistros,
        String status,
        LocalDateTime dataExecucao
) {
    public static LoteDto fromEntity(LoteIngestao lote) {
        if (lote == null) return null;
        FonteDado fonte = lote.getFonteDado();
        Long fonteId = null;
        String fonteNome = null;
        if (fonte != null) {
            try {
                fonteId = fonte.getId();
                fonteNome = fonte.getNome();
            } catch (Exception e) {
                fonteNome = "Fonte #" + (fonteId != null ? fonteId : "");
            }
        }
        return new LoteDto(
                lote.getId(),
                fonteId,
                fonteNome != null ? fonteNome : (fonteId != null ? "Fonte #" + fonteId : "—"),
                lote.getOrigemReferencia(),
                lote.getQuantidadeRegistros() != null ? lote.getQuantidadeRegistros() : 0,
                lote.getStatus(),
                lote.getDataExecucao()
        );
    }
}
