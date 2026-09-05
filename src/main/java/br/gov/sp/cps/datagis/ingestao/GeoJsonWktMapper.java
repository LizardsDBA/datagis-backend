package br.gov.sp.cps.datagis.ingestao;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Converte a geometria de uma feature GeoJSON pra WKT (texto), sem validar nada.
 * Feito com JTS puro, sem precisar do GeoTools so pra isso.
 *
 * So trata Polygon e MultiPolygon, que e o que o CAR manda. Qualquer outro tipo
 * da erro na hora, de proposito, pra nao gravar um WKT errado sem perceber.
 */
@Component
public class GeoJsonWktMapper {

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final WKTWriter wktWriter = new WKTWriter();

    public String paraWkt(JsonNode geometryNode) {
        String tipo = geometryNode.path("type").asString();
        JsonNode coordinates = geometryNode.path("coordinates");

        Geometry geometria = switch (tipo) {
            case "Polygon" -> polygonDe(coordinates);
            case "MultiPolygon" -> multiPolygonDe(coordinates);
            default -> throw new IllegalArgumentException(
                    "Tipo de geometria GeoJSON não suportado: " + tipo);
        };
        return wktWriter.write(geometria);
    }

    private Polygon polygonDe(JsonNode polygonCoords) {
        LinearRing shell = anelDe(polygonCoords.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, polygonCoords.size() - 1)];
        for (int i = 1; i < polygonCoords.size(); i++) {
            holes[i - 1] = anelDe(polygonCoords.get(i));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private Geometry multiPolygonDe(JsonNode multiPolygonCoords) {
        Polygon[] poligonos = new Polygon[multiPolygonCoords.size()];
        for (int i = 0; i < multiPolygonCoords.size(); i++) {
            poligonos[i] = polygonDe(multiPolygonCoords.get(i));
        }
        return geometryFactory.createMultiPolygon(poligonos);
    }

    private LinearRing anelDe(JsonNode anelCoords) {
        Coordinate[] coordenadas = new Coordinate[anelCoords.size()];
        for (int i = 0; i < anelCoords.size(); i++) {
            JsonNode par = anelCoords.get(i);
            coordenadas[i] = new Coordinate(par.get(0).doubleValue(), par.get(1).doubleValue());
        }
        return geometryFactory.createLinearRing(coordenadas);
    }
}
