package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;

/** O que a tela ve de uma fonte cadastrada. */
public record FonteDadoResponse(
        Long id,
        String nome,
        String orgao,
        String tipoAquisicao,
        String urlOrigem,
        String cronExpressao,
        String formatoEsperado,
        Boolean ativo,
        LocalDateTime ultimaExecucaoEm,
        LocalDateTime dataAdd) {

    public static FonteDadoResponse de(FonteDado fonte) {
        return new FonteDadoResponse(
                fonte.getId(),
                fonte.getNome(),
                fonte.getOrgao(),
                fonte.getTipoAquisicao(),
                fonte.getUrlOrigem(),
                fonte.getCronExpressao(),
                fonte.getFormatoEsperado(),
                fonte.getAtivo(),
                fonte.getUltimaExecucaoEm(),
                fonte.getDataAdd());
    }
}
