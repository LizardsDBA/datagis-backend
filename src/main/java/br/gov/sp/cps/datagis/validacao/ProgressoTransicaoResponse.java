package br.gov.sp.cps.datagis.validacao;

import java.time.LocalDateTime;

/**
 * DTO para retorno do endpoint de monitoramento de progresso de lote na esteira Silver.
 */
public record ProgressoTransicaoResponse(
        Long loteId,
        String fase,
        int registrosProcessados,
        int totalRegistros,
        int percentual,
        int errosEncontrados,
        String detalhe,
        String statusFinal,
        LocalDateTime iniciadoEm,
        LocalDateTime concluidoEm
) {
    public static ProgressoTransicaoResponse de(ProgressoTransicao progresso) {
        if (progresso == null) {
            return ocioso(null);
        }
        return new ProgressoTransicaoResponse(
                progresso.loteId(),
                progresso.fase(),
                progresso.registrosProcessados(),
                progresso.totalRegistros(),
                progresso.percentual(),
                progresso.errosEncontrados(),
                progresso.detalhe(),
                progresso.statusFinal(),
                progresso.iniciadoEm(),
                progresso.concluidoEm()
        );
    }

    public static ProgressoTransicaoResponse ocioso(Long loteId) {
        return new ProgressoTransicaoResponse(loteId, "OCIOSO", 0, 0, 0, 0, null, null, null, null);
    }
}
