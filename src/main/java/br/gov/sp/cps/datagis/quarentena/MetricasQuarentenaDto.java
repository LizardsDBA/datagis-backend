package br.gov.sp.cps.datagis.quarentena;

import java.util.Map;

/**
 * Resumo quantitativo e métricas consolidadas da Quarentena.
 */
public record MetricasQuarentenaDto(
        long totalRegistros,
        long totalPendentes,
        long totalEmAnalise,
        long totalCorrigidos,
        long totalDescartados,
        Map<String, Long> totalPorTipoErro,
        Map<String, Long> totalPorEntidade
) {}
