package br.gov.sp.cps.datagis.controle.monitoramento;

/**
 * Item de status de ingestão da camada Bronze para o monitoramento global.
 */
public record MonitoramentoIngestaoItem(
        Long fonteId,
        String fonteNome,
        String fase,
        long registrosProcessados,
        Long totalEstimado,
        int percentual,
        String detalhe,
        boolean emExecucao
) {}
