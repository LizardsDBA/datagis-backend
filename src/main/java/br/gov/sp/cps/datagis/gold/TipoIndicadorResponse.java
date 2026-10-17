package br.gov.sp.cps.datagis.gold;

import java.time.LocalDateTime;

/**
 * Representacao de saida de um tipo de indicador para consumo pela
 * API/Frontend.
 *
 * <p>
 * Desde a V076 carrega tambem a dimensao, o sentido e os limites de faixa: e
 * com isso
 * que a tela de consulta (LIZARDS-34) monta os grupos de cabecalho e decide a
 * cor de cada
 * valor, sem precisar de um segundo endpoint so pra catalogo.
 */
public record TipoIndicadorResponse(
        Long id,
        String codigo,
        String descricao,
        Boolean temFonte,
        Boolean ativo,
        String dimensao,
        String sentido,
        Integer limiteBom,
        Integer limiteAtencao,
        Integer ordemExibicao,
        LocalDateTime dataAdd) {
    public static TipoIndicadorResponse de(TipoIndicador tipo) {
        return new TipoIndicadorResponse(
                tipo.getId(),
                tipo.getCodigo(),
                tipo.getDescricao(),
                tipo.getTemFonte(),
                tipo.getAtivo(),
                tipo.getDimensao(),
                tipo.getSentido(),
                tipo.getLimiteBom(),
                tipo.getLimiteAtencao(),
                tipo.getOrdemExibicao(),
                tipo.getDataAdd());
    }
}