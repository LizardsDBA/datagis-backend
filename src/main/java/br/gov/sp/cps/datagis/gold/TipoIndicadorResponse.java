package br.gov.sp.cps.datagis.gold;

import java.time.LocalDateTime;

/**
 * Representação de saída de um tipo de indicador para consumo pela API/Frontend.
 */
public record TipoIndicadorResponse(
        Long id,
        String codigo,
        String descricao,
        Boolean temFonte,
        Boolean ativo,
        LocalDateTime dataAdd
) {
    public static TipoIndicadorResponse de(TipoIndicador tipo) {
        return new TipoIndicadorResponse(
                tipo.getId(),
                tipo.getCodigo(),
                tipo.getDescricao(),
                tipo.getTemFonte(),
                tipo.getAtivo(),
                tipo.getDataAdd()
        );
    }
}
