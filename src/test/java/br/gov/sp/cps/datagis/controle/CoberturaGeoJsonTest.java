package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.io.WKTReader;
import tools.jackson.databind.json.JsonMapper;

class CoberturaGeoJsonTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            POINT (-50 -25)|{"type":"Point","coordinates":[-50.0,-25.0]}
            LINESTRING (-50 -25, -49 -24)|{"type":"LineString","coordinates":[[-50.0,-25.0],[-49.0,-24.0]]}
            POLYGON ((0 0,4 0,4 4,0 0),(2 1,3 1,3 2,2 1))|{"type":"Polygon","coordinates":[[[0.0,0.0],[4.0,0.0],[4.0,4.0],[0.0,0.0]],[[2.0,1.0],[3.0,1.0],[3.0,2.0],[2.0,1.0]]]}
            MULTIPOINT ((-50 -25),(-49 -24))|{"type":"MultiPoint","coordinates":[[-50.0,-25.0],[-49.0,-24.0]]}
            MULTILINESTRING ((0 0,1 1),(2 2,3 3))|{"type":"MultiLineString","coordinates":[[[0.0,0.0],[1.0,1.0]],[[2.0,2.0],[3.0,3.0]]]}
            MULTIPOLYGON (((0 0,1 0,1 1,0 0)),((2 2,3 2,3 3,2 2)))|{"type":"MultiPolygon","coordinates":[[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]],[[[2.0,2.0],[3.0,2.0],[3.0,3.0],[2.0,2.0]]]]}
            GEOMETRYCOLLECTION (POINT (1 2), LINESTRING (0 0,1 1))|{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1.0,2.0]},{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0]]}]}
            POINT EMPTY|{"type":"Point","coordinates":[]}
            POLYGON EMPTY|{"type":"Polygon","coordinates":[]}
            POINT Z (1 2 3)|{"type":"Point","coordinates":[1.0,2.0,3.0]}
            """)
    void serializaEstruturaGeoJsonSemGettersJts(String wkt, String esperado) throws Exception {
        var geometria = new WKTReader().read(wkt);
        geometria.setSRID(4674);
        var json = mapper.writeValueAsString(CoberturaGeoJson.de(geometria));
        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(esperado));
        assertThat(geometria.getSRID()).isEqualTo(4674);
    }

    @Test
    void preservaCoberturaNula() {
        assertThat(CoberturaGeoJson.de(null)).isNull();
    }
}
