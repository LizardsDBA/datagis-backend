package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Achamos ao vivo que o ArcGIS corta em ~2000 registros por página por padrão (Embargos
 * IBAMA do IAT/PR, que tem ~2113 reais — só 2000 vinham) — esse teste garante que a
 * detecção de família e a montagem da URL de página continuam corretas.
 */
class PaginadorUrlGeoJsonTest {

    @Test
    void detectaWfsPeloServiceNaUrl() {
        assertThat(PaginadorUrlGeoJson.detectarFamilia(
                "https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature"))
                .isEqualTo(PaginadorUrlGeoJson.Familia.WFS);
    }

    @Test
    void detectaArcgisPeloCaminhoFeatureServer() {
        assertThat(PaginadorUrlGeoJson.detectarFamilia(
                "https://geo.iat.pr.gov.br/server/rest/services/Embargos/FeatureServer/9/query?f=geojson"))
                .isEqualTo(PaginadorUrlGeoJson.Familia.ARCGIS);
    }

    @Test
    void detectaArcgisPeloCaminhoMapServer() {
        assertThat(PaginadorUrlGeoJson.detectarFamilia(
                "https://geo.iat.pr.gov.br/server/rest/services/FocosCalor/MapServer/0/query?f=geojson"))
                .isEqualTo(PaginadorUrlGeoJson.Familia.ARCGIS);
    }

    @Test
    void naoReconheceOutrasUrlsComoPaginaveis() {
        assertThat(PaginadorUrlGeoJson.detectarFamilia("https://exemplo.gov.br/dados/arquivo.geojson"))
                .isEqualTo(PaginadorUrlGeoJson.Familia.DESCONHECIDA);
    }

    @Test
    void montaUrlDePaginaWfsComStartIndexEMaxFeatures() {
        String url = PaginadorUrlGeoJson.montarUrlPagina(
                "https://geoserver.car.gov.br/wfs?service=WFS&version=2.0.0&request=GetFeature",
                PaginadorUrlGeoJson.Familia.WFS, 2000);

        assertThat(url).contains("&startIndex=2000");
        assertThat(url).contains("&maxFeatures=" + PaginadorUrlGeoJson.TAMANHO_PAGINA);
    }

    @Test
    void montaUrlDePaginaArcgisComResultOffsetEResultRecordCount() {
        String url = PaginadorUrlGeoJson.montarUrlPagina(
                "https://geo.iat.pr.gov.br/server/rest/services/Embargos/FeatureServer/9/query?f=geojson",
                PaginadorUrlGeoJson.Familia.ARCGIS, 2000);

        assertThat(url).contains("&resultOffset=2000");
        assertThat(url).contains("&resultRecordCount=" + PaginadorUrlGeoJson.TAMANHO_PAGINA);
    }

    @Test
    void removeParametroDePaginacaoJaExistenteAntesDeMontarNovaPagina() {
        // O valor antigo aqui é de propósito diferente de TAMANHO_PAGINA (500 vs 1000),
        // pra provar que o parâmetro salvo foi REMOVIDO e não só coincidiu com o novo.
        String url = PaginadorUrlGeoJson.montarUrlPagina(
                "https://terrabrasilis.dpi.inpe.br/ows?service=WFS&maxFeatures=500",
                PaginadorUrlGeoJson.Familia.WFS, 0);

        assertThat(url).containsOnlyOnce("maxFeatures=");
        assertThat(url).doesNotContain("maxFeatures=500");
        assertThat(url).contains("maxFeatures=" + PaginadorUrlGeoJson.TAMANHO_PAGINA);
    }

    @Test
    void fonteDesconhecidaVoltaComAUrlIntacta() {
        String urlOriginal = "https://exemplo.gov.br/dados/arquivo.geojson";
        assertThat(PaginadorUrlGeoJson.montarUrlPagina(urlOriginal, PaginadorUrlGeoJson.Familia.DESCONHECIDA, 5000))
                .isEqualTo(urlOriginal);
    }
}
