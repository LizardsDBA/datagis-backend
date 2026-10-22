package br.gov.sp.cps.datagis.controle.monitoramento;

import java.util.List;

/**
 * Item de status da publicação na camada Gold para o monitoramento global.
 */
public record MonitoramentoGoldItem(
        boolean emExecucao,
        int percentualGeral,
        String statusIndicadores,
        Long indicadoresProcessados,
        Long indicadoresTotal,
        List<CamadaMapaStatusItem> camadasMapa
) {
    public record CamadaMapaStatusItem(
            String codigoCamada,
            String status,
            long processado,
            Long total
    ) {}
}
