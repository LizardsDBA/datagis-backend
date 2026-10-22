package br.gov.sp.cps.datagis.controle.monitoramento;

/**
 * Item de status de transição e liberação para a camada Silver para o monitoramento global.
 */
public record MonitoramentoSilverItem(
        Long loteId,
        String fonteNome,
        String fase,
        int registrosProcessados,
        int totalRegistros,
        int percentual,
        int errosEncontrados,
        String detalhe,
        String statusFinal,
        boolean emExecucao
) {}
