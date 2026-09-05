package br.gov.sp.cps.datagis.controle;

import jakarta.validation.constraints.NotBlank;

/** Dados que vem do formulario de cadastro de fonte. */
public record FonteDadoRequest(
        @NotBlank String nome,
        String orgao,
        @NotBlank String tipoAquisicao,
        String urlOrigem,
        String cronExpressao,
        String formatoEsperado,
        Boolean ativo) {
}
