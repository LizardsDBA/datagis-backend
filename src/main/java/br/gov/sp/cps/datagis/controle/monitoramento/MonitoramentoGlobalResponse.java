package br.gov.sp.cps.datagis.controle.monitoramento;

import java.util.List;

/**
 * Resposta agregada contendo o status de todas as esteiras (Bronze, Silver e Gold)
 * para o painel de monitoramento global no frontend.
 */
public record MonitoramentoGlobalResponse(
        int totalEmAndamento,
        List<MonitoramentoIngestaoItem> ingestoes,
        List<MonitoramentoSilverItem> migracoesSilver,
        MonitoramentoGoldItem migracaoGold
) {}
