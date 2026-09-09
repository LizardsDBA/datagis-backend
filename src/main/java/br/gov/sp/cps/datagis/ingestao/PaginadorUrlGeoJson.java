package br.gov.sp.cps.datagis.ingestao;

import java.util.regex.Pattern;

/**
 * Decide como paginar uma URL de fonte GeoJSON (WFS ou ArcGIS REST) a partir da própria
 * URL cadastrada, sem precisar de nenhuma configuração extra por fonte. Existe pra
 * resolver um problema real: sem paginação, cada servidor aplica o próprio limite
 * padrão de página e a ingestão simplesmente perde o resto — foi o que aconteceu com os
 * Embargos IBAMA do IAT/PR (ArcGIS cortou em ~2000 de ~2113 reais).
 *
 * <p>Duas famílias reconhecidas pela própria URL:
 * <ul>
 *   <li><b>WFS</b> (GeoServer) — pagina com {@code startIndex} (offset) +
 *       {@code maxFeatures} (tamanho da página). GeoServer aceita os dois mesmo em
 *       WFS 1.1.0 (extensão dele), não só na paginação "oficial" do WFS 2.0.0 — evita
 *       depender da versão declarada em cada URL cadastrada.</li>
 *   <li><b>ArcGIS REST</b> (FeatureServer/MapServer) — pagina com {@code resultOffset}
 *       + {@code resultRecordCount}.</li>
 * </ul>
 *
 * <p>Qualquer parâmetro de paginação que já exista na URL cadastrada (ex.: um
 * {@code maxFeatures} fixo colocado manualmente antes de a paginação de verdade
 * existir) é removido antes de montar cada página — quem manda no tamanho da página é
 * sempre este componente, nunca o que está salvo em {@code fonte_dado.url_origem}.
 *
 * <p>Fonte de família {@link Familia#DESCONHECIDA} (ex.: download direto de um arquivo
 * estático) não é paginada — a URL volta exatamente como estava, uma chamada só, igual
 * sempre foi.
 */
final class PaginadorUrlGeoJson {

    /** Tamanho de página usado nas duas famílias. */
    static final int TAMANHO_PAGINA = 1000;

    /**
     * Trava de segurança: nenhuma fonte real do catálogo hoje chega perto disso (a
     * maior é o CAR estadual inteiro, ~556 mil registros = ~556 páginas). Existe só
     * pra nunca entrar num loop infinito silencioso se algum servidor se comportar de
     * um jeito inesperado (ex.: sempre devolver exatamente TAMANHO_PAGINA registros e
     * nunca uma página menor pra sinalizar o fim).
     */
    static final int MAX_PAGINAS = 1000;

    private static final Pattern PARAMS_PAGINACAO = Pattern.compile(
            "&(startIndex|count|maxFeatures|resultOffset|resultRecordCount)=[^&]*",
            Pattern.CASE_INSENSITIVE);

    enum Familia {
        WFS,
        ARCGIS,
        DESCONHECIDA
    }

    private PaginadorUrlGeoJson() {
    }

    static Familia detectarFamilia(String url) {
        String minuscula = url.toLowerCase();
        if (minuscula.contains("service=wfs")) {
            return Familia.WFS;
        }
        if (minuscula.contains("/featureserver/") || minuscula.contains("/mapserver/")) {
            return Familia.ARCGIS;
        }
        return Familia.DESCONHECIDA;
    }

    /** Monta a URL de uma página específica (offset já em número de registros, não em página). */
    static String montarUrlPagina(String urlBase, Familia familia, int offset) {
        if (familia == Familia.DESCONHECIDA) {
            return urlBase;
        }
        String urlLimpa = PARAMS_PAGINACAO.matcher(urlBase).replaceAll("");
        return switch (familia) {
            case WFS -> urlLimpa + "&startIndex=" + offset + "&maxFeatures=" + TAMANHO_PAGINA;
            case ARCGIS -> urlLimpa + "&resultOffset=" + offset + "&resultRecordCount=" + TAMANHO_PAGINA;
            case DESCONHECIDA -> urlBase;
        };
    }
}
