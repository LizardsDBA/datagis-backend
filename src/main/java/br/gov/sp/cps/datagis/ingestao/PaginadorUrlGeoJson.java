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
 *
 * <p><b>Tamanho de página nem sempre é seguro em 1000:</b> achado ao vivo com a Malha
 * Municipal do IBGE/IAT-PR — o mesmo servidor ArcGIS que devolve 1000+ pontos numa
 * página sem problema quebra com {@code HTTP 500} ao tentar converter ~500 polígonos
 * municipais (mais complexos/pesados) pra GeoJSON de uma vez. Em vez de cravar um
 * tamanho de página menor pra todo mundo (penaliza fontes leves com mais chamadas à
 * toa) ou tentar adivinhar por fonte, {@link IngestaoGeoJsonExecutor} reduz o tamanho
 * pela metade e tenta de novo quando uma página falha, até {@link #TAMANHO_MINIMO_PAGINA}.
 */
final class PaginadorUrlGeoJson {

    /** Tamanho de página inicial, usado nas duas famílias. */
    static final int TAMANHO_PAGINA = 1000;

    /**
     * Piso da redução automática de tamanho de página (ver javadoc da classe). Abaixo
     * disso, se ainda estiver falhando, o problema não é tamanho de página — desiste e
     * deixa o erro real subir.
     */
    static final int TAMANHO_MINIMO_PAGINA = 50;

    /**
     * Trava de segurança contra loop infinito silencioso (ex.: servidor sempre devolve
     * exatamente TAMANHO_PAGINA registros e nunca uma página menor pra sinalizar o
     * fim). Não é um teto de volume de dados - é só um número de chamadas HTTP
     * improvável de acontecer de verdade.
     *
     * [2026-10-31] Era 1000, calibrado só contra o CAR (~556 mil registros, ~556
     * páginas a TAMANHO_PAGINA cheio). Estourou ao vivo na ingestao real da Vegetacao
     * Nativa (IAT/PR, ~393 mil registros): geometria muito mais densa que o perímetro
     * do CAR fez o motor de retentativa (ver javadoc da classe) reduzir bastante o
     * tamanho de pagina em boa parte do percurso, consumindo 1000 chamadas pra so
     * 371.250 registros (media de ~371/pagina, bem abaixo de TAMANHO_PAGINA) -
     * faltavam ~22 mil registros quando a trava disparou. Subido pra 4000 pra dar
     * margem real (cobre até ~200 mil registros mesmo presa no piso de 50/pagina).
     */
    static final int MAX_PAGINAS = 4000;

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
    static String montarUrlPagina(String urlBase, Familia familia, int offset, int tamanhoPagina) {
        if (familia == Familia.DESCONHECIDA) {
            return urlBase;
        }
        String urlLimpa = PARAMS_PAGINACAO.matcher(urlBase).replaceAll("");
        return switch (familia) {
            case WFS -> urlLimpa + "&startIndex=" + offset + "&maxFeatures=" + tamanhoPagina;
            case ARCGIS -> urlLimpa + "&resultOffset=" + offset + "&resultRecordCount=" + tamanhoPagina;
            case DESCONHECIDA -> urlBase;
        };
    }
}
