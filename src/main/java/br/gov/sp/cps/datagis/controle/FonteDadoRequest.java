package br.gov.sp.cps.datagis.controle;

import jakarta.validation.constraints.NotBlank;

/** Dados que vem do formulario de cadastro/edicao de fonte. */
public record FonteDadoRequest(
        @NotBlank String nome,
        String orgao,
        @NotBlank String tipoAquisicao,
        String urlOrigem,
        String cronExpressao,
        String formatoEsperado,
        String campoGeometriaLat,
        String campoGeometriaLon,
        String separadorCsv,
        Boolean ativo,
        String entidadeSilver) {

    public FonteDadoRequest(
            String nome, String orgao, String tipoAquisicao, String urlOrigem,
            String cronExpressao, String formatoEsperado, String campoGeometriaLat,
            String campoGeometriaLon, String separadorCsv, Boolean ativo) {
        this(nome, orgao, tipoAquisicao, urlOrigem, cronExpressao, formatoEsperado,
             campoGeometriaLat, campoGeometriaLon, separadorCsv, ativo, null);
    }
}
