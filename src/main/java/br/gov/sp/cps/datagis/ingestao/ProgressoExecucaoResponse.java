package br.gov.sp.cps.datagis.ingestao;

import java.time.LocalDateTime;

/** DTO de resposta de {@code GET /api/fontes/{id}/progresso} — já traz o percentual calculado. */
public record ProgressoExecucaoResponse(
        String fase,
        long registrosProcessados,
        Long totalEstimado,
        Integer percentual,
        String detalhe,
        LocalDateTime atualizadoEm) {

    public static ProgressoExecucaoResponse de(ProgressoExecucao progresso) {
        return new ProgressoExecucaoResponse(
                progresso.fase().name(),
                progresso.registrosProcessados(),
                progresso.totalEstimado(),
                calcularPercentual(progresso.registrosProcessados(), progresso.totalEstimado()),
                progresso.detalhe(),
                progresso.atualizadoEm());
    }

    /** Sem execução em andamento (ou nunca executada) — {@code fase} vem null, a tela trata como ocioso. */
    public static ProgressoExecucaoResponse ocioso() {
        return new ProgressoExecucaoResponse(null, 0, null, null, null, null);
    }

    private static Integer calcularPercentual(long processados, Long total) {
        if (total == null || total <= 0) {
            return null;
        }
        return (int) Math.min(100, Math.round((processados * 100.0) / total));
    }
}
