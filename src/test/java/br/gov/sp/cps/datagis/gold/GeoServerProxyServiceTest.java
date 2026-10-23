package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Proxy do GeoServer (LIZARDS-35): o que passa, como chega no GeoServer, e o que e
 * barrado antes de sair do backend.
 */
class GeoServerProxyServiceTest {

    private static final GeoServerProperties PROPS =
            new GeoServerProperties("http://geoserver:8080/geoserver/", Duration.ofSeconds(5));

    private MockRestServiceServer geoServer;
    private GeoServerProxyService service;
    private final AtomicReference<URI> uriRecebida = new AtomicReference<>();

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        geoServer = MockRestServiceServer.bindTo(builder).build();
        service = new GeoServerProxyService(PROPS, builder);
    }

    @Test
    void encaminhaGetMapProWmsDoWorkspaceEDevolveAImagem() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        geoServer.expect(method(HttpMethod.GET))
                .andExpect(req -> uriRecebida.set(req.getURI()))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        ResponseEntity<byte[]> resposta = service.encaminhar("wms", parametros(
                "SERVICE", "WMS", "REQUEST", "GetMap",
                "LAYERS", "datagis:vw_mapa_municipio,datagis:vw_mapa_imovel", "FORMAT", "image/png"));

        geoServer.verify();
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(resposta.getBody()).isEqualTo(png);
        // Barra no fim da URL configurada nao duplica; vai pro servico virtual do workspace.
        assertThat(uriRecebida.get().getPath()).isEqualTo("/geoserver/datagis/wms");
        assertThat(decodificado(uriRecebida.get()))
                .contains("LAYERS=datagis:vw_mapa_municipio,datagis:vw_mapa_imovel")
                .contains("REQUEST=GetMap");
    }

    @Test
    void filtroIntersectsComQuerySingleChegaIntactoNoGeoServer() {
        String filtro = "INTERSECTS(GEOMETRIA,querySingle('datagis:vw_mapa_municipio','GEOMETRIA','COD_NATURAL=''4119905'''))";
        geoServer.expect(method(HttpMethod.GET))
                .andExpect(req -> uriRecebida.set(req.getURI()))
                .andRespond(withSuccess("<wfs:FeatureCollection numberMatched=\"2654\"/>", MediaType.APPLICATION_XML));

        service.encaminhar("wfs", parametros(
                "SERVICE", "WFS", "REQUEST", "GetFeature", "TYPENAMES", "datagis:vw_mapa_imovel",
                "CQL_FILTER", filtro));

        assertThat(uriRecebida.get().getPath()).isEqualTo("/geoserver/datagis/wfs");
        assertThat(decodificado(uriRecebida.get())).contains("CQL_FILTER=" + filtro);
    }

    @Test
    void sinalDeMaisNaoViraEspacoNoCaminho() {
        geoServer.expect(method(HttpMethod.GET))
                .andExpect(req -> uriRecebida.set(req.getURI()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.encaminhar("wfs", parametros(
                "REQUEST", "GetFeature", "TYPENAMES", "vw_mapa_foco_calor", "CQL_FILTER", "ROTULO = 'a+b'"));

        assertThat(uriRecebida.get().getRawQuery()).contains("a%2Bb");
    }

    @Test
    void nomeDeParametroNaoDiferenciaMaiuscula() {
        geoServer.expect(method(HttpMethod.GET)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.encaminhar("WFS", parametros("request", "getfeature", "typeNames", "datagis:VW_MAPA_IMOVEL"));

        geoServer.verify();
    }

    @Test
    void erroDoGeoServerPassaAdianteComOMesmoStatus() {
        geoServer.expect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("fora do ar")
                        .contentType(MediaType.TEXT_PLAIN));

        ResponseEntity<byte[]> resposta = service.encaminhar("wms",
                parametros("REQUEST", "GetMap", "LAYERS", "vw_mapa_imovel"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(new String(resposta.getBody(), StandardCharsets.UTF_8)).isEqualTo("fora do ar");
    }

    @Test
    void geoServerInacessivelViraIllegalStateException() {
        geoServer.expect(method(HttpMethod.GET)).andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> service.encaminhar("wms",
                parametros("REQUEST", "GetMap", "LAYERS", "vw_mapa_imovel")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GeoServer indisponivel");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "servico fora da lista              | rest | REQUEST | GetMap",
            "WMS sem operacao                   | wms  | FORMAT  | image/png",
            "GetFeatureInfo                     | wms  | REQUEST | GetFeatureInfo",
            "GetCapabilities                    | wms  | REQUEST | GetCapabilities",
            "Transaction (WFS-T)                | wfs  | REQUEST | Transaction",
            "operacao de WMS no WFS             | wfs  | REQUEST | GetMap",
    })
    void recusaServicoOuOperacaoForaDaLista(String caso, String servico, String nome, String valor) {
        assertThatThrownBy(() -> service.encaminhar(servico, parametros(nome, valor)))
                .isInstanceOf(IllegalArgumentException.class);
        geoServer.verify();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "camada fora da lista          | LAYERS       | datagis:vw_mapa_municipio,datagis:outra",
            "camada de outro workspace     | LAYERS       | topp:vw_mapa_imovel",
            "tabela da Gold sem view       | LAYERS       | feature_publicada",
            "camada na legenda             | LAYER        | datagis:imovel_publicado",
            "camada em QUERY_LAYERS        | QUERY_LAYERS | vw_mapa_imovel,silver_municipio",
    })
    void recusaCamadaForaDaListaNoWms(String caso, String nome, String valor) {
        assertThatThrownBy(() -> service.encaminhar("wms", parametros("REQUEST", "GetMap", nome, valor)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Camada nao permitida");
        geoServer.verify();
    }

    @Test
    void recusaCamadaForaDaListaDentroDoFiltro() {
        String filtro = "INTERSECTS(GEOMETRIA,querySingle('datagis:imovel_publicado','GEOMETRIA','1=1'))";

        assertThatThrownBy(() -> service.encaminhar("wfs", parametros(
                "REQUEST", "GetFeature", "TYPENAMES", "vw_mapa_imovel", "CQL_FILTER", filtro)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datagis:imovel_publicado");
        geoServer.verify();
    }

    @Test
    void recusaQueryCollectionComCamadaForaDaLista() {
        String filtro = "INTERSECTS(GEOMETRIA, collectGeometries(queryCollection('outra','GEOMETRIA','INCLUDE')))";

        assertThatThrownBy(() -> service.encaminhar("wfs", parametros(
                "REQUEST", "GetFeature", "TYPENAMES", "vw_mapa_imovel", "CQL_FILTER", filtro)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"SLD,http://evil.example/estilo.sld", "sld_body,<StyledLayerDescriptor/>"})
    void recusaEstiloVindoDeFora(String nome, String valor) {
        assertThatThrownBy(() -> service.encaminhar("wms",
                parametros("REQUEST", "GetMap", "LAYERS", "vw_mapa_imovel", nome, valor)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parametro nao permitido");
        geoServer.verify();
    }

    @Test
    void recusaServiceQueNaoConfereComOCaminho() {
        assertThatThrownBy(() -> service.encaminhar("wfs",
                parametros("SERVICE", "WMS", "REQUEST", "GetFeature", "TYPENAMES", "vw_mapa_imovel")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nao confere");
    }

    private static MultiValueMap<String, String> parametros(String... chaveValor) {
        MultiValueMap<String, String> mapa = new LinkedMultiValueMap<>();
        for (int i = 0; i < chaveValor.length; i += 2) {
            mapa.add(chaveValor[i], chaveValor[i + 1]);
        }
        return mapa;
    }

    private static String decodificado(URI uri) {
        return URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
    }
}
