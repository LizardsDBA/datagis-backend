package br.gov.sp.cps.datagis.validacao;

import java.time.LocalDateTime;

/**
 * Estado em memória do progresso de reprocessamento ou liberação de um lote de ingestão.
 */
public record ProgressoTransicao(
        Long loteId,
        String fase,
        int registrosProcessados,
        int totalRegistros,
        int errosEncontrados,
        String detalhe,
        String statusFinal,
        LocalDateTime iniciadoEm,
        LocalDateTime concluidoEm
) {
    public int percentual() {
        if (totalRegistros <= 0) return 0;
        return (int) Math.min(100, Math.round(((double) registrosProcessados / totalRegistros) * 100));
    }
}
