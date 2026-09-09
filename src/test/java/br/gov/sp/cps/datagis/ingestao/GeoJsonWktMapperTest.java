package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Achamos em produção que faltava Point (focos de calor do IAT/PR quebrou com
 * "Tipo de geometria GeoJSON não suportado: Point") — esse teste existe pra não
 * repetir esse tipo de descoberta só ao vivo de novo.
 */
class GeoJsonWktMapperTest {

    private final GeoJsonWktMapper mapper = new GeoJsonWktMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deveConverterPoint() {
        String wkt = mapper.paraWkt(geometria("{\"type\":\"Point\",\"coordinates\":[-49.27,-25.42]}"));
        assertThat(wkt).isEqualTo("POINT (-49.27 -25.42)");
    }

    @Test
    void deveConverterMultiPoint() {
        String wkt = mapper.paraWkt(
                geometria("{\"type\":\"MultiPoint\",\"coordinates\":[[-49.27,-25.42],[-48.9,-25.1]]}"));
        assertThat(wkt).startsWith("MULTIPOINT");
    }

    @Test
    void deveConverterPolygon() {
        String wkt = mapper.paraWkt(geometria(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"));
        assertThat(wkt).startsWith("POLYGON");
    }

    @Test
    void deveConverterMultiPolygon() {
        String wkt = mapper.paraWkt(geometria(
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0],[1,0],[1,1],[0,1],[0,0]]]]}"));
        assertThat(wkt).startsWith("MULTIPOLYGON");
    }

    @Test
    void deveFalharAltoParaTipoDesconhecidoEmVezDeGravarWktErrado() {
        JsonNode geometria = geometria("{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]}");
        assertThatThrownBy(() -> mapper.paraWkt(geometria)).isInstanceOf(IllegalArgumentException.class);
    }

    private JsonNode geometria(String json) {
        return objectMapper.readTree(json);
    }
}
