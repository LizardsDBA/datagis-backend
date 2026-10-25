package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;

/** O que a tela ve de uma fonte cadastrada. */
public record FonteDadoResponse(
        Long id,
        String nome,
        String orgao,
        String tipoAquisicao,
        String entidadeSilver,
        String urlOrigem,
        String cronExpressao,
        String formatoEsperado,
        String campoGeometriaLat,
        String campoGeometriaLon,
        String separadorCsv,
        Boolean ativo,
        LocalDateTime ultimaExecucaoEm,
        Long ultimaDuracaoMs,
        String ultimoStatusExecucao,
        String ultimoErroMensagem,
        LocalDateTime dataAdd,
        Boolean mapeamentoCompleto,
        Integer totalColunasSilver,
        Integer totalColunasMapeadas,
        java.util.List<String> colunasSilverFaltantes,
        // No fuso do servidor, como as outras datas desta resposta
        LocalDateTime proximaExecucaoEm) {

    public static FonteDadoResponse de(FonteDado fonte) {
        return de(fonte, null);
    }

    public static FonteDadoResponse de(
            FonteDado fonte,
            ValidacaoMapeamentoFonteService.StatusMapeamentoFonte status) {
        return new FonteDadoResponse(
                fonte.getId(),
                fonte.getNome(),
                fonte.getOrgao(),
                fonte.getTipoAquisicao(),
                fonte.getEntidadeSilver(),
                fonte.getUrlOrigem(),
                fonte.getCronExpressao(),
                fonte.getFormatoEsperado(),
                fonte.getCampoGeometriaLat(),
                fonte.getCampoGeometriaLon(),
                fonte.getSeparadorCsv(),
                fonte.getAtivo(),
                fonte.getUltimaExecucaoEm(),
                fonte.getUltimaDuracaoMs(),
                fonte.getUltimoStatusExecucao(),
                fonte.getUltimoErroMensagem(),
                fonte.getDataAdd(),
                status != null ? status.completo() : false,
                status != null ? status.totalColunasEsperadas() : 0,
                status != null ? status.totalColunasMapeadas() : 0,
                status != null ? status.colunasFaltantes() : java.util.Collections.emptyList(),
                fonte.getProximaExecucaoEm());
    }
}
