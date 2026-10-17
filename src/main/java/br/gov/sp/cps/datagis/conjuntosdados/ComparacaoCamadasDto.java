package br.gov.sp.cps.datagis.conjuntosdados;

import java.util.List;

/**
 * Resultado da comparação de um mesmo registro entre duas camadas vizinhas (Bronze→Silver
 * ou Silver→Gold), no estilo de um diff: cada campo aparece uma vez, com o valor de cada
 * lado e a situação. {@code destinoEncontrado=false} quando o registro não existe na
 * camada de destino — nesse caso {@code mensagem} explica o motivo e {@code campos} vem vazio.
 */
public record ComparacaoCamadasDto(
        Long registroBrutoId,
        String camadaOrigem,
        String camadaDestino,
        boolean destinoEncontrado,
        String mensagem,
        Resumo resumo,
        List<DiferencaCampo> campos) {

    public static final String IGUAL = "IGUAL";
    public static final String DIFERENTE = "DIFERENTE";
    public static final String SO_ORIGEM = "SO_ORIGEM";
    public static final String SO_DESTINO = "SO_DESTINO";

    public record DiferencaCampo(String campo, Object valorOrigem, Object valorDestino, String situacao) {
    }

    public record Resumo(int iguais, int diferentes, int soOrigem, int soDestino) {
    }

    public static ComparacaoCamadasDto semDestino(
            Long registroBrutoId, String camadaOrigem, String camadaDestino, String mensagem) {
        return new ComparacaoCamadasDto(
                registroBrutoId, camadaOrigem, camadaDestino, false, mensagem, new Resumo(0, 0, 0, 0), List.of());
    }

    public static ComparacaoCamadasDto de(
            Long registroBrutoId, String camadaOrigem, String camadaDestino, List<DiferencaCampo> campos) {
        int iguais = 0;
        int diferentes = 0;
        int soOrigem = 0;
        int soDestino = 0;
        for (DiferencaCampo campo : campos) {
            switch (campo.situacao()) {
                case IGUAL -> iguais++;
                case DIFERENTE -> diferentes++;
                case SO_ORIGEM -> soOrigem++;
                default -> soDestino++;
            }
        }
        return new ComparacaoCamadasDto(
                registroBrutoId, camadaOrigem, camadaDestino, true, null,
                new Resumo(iguais, diferentes, soOrigem, soDestino), campos);
    }
}
