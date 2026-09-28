package br.gov.sp.cps.datagis.controle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** Converte apenas a estrutura espacial, sem expor getters internos do JTS ao Jackson. */
final class CoberturaGeoJson {
    private CoberturaGeoJson() {}

    static Map<String, Object> de(Geometry geometria) {
        if (geometria == null) return null;
        if (geometria instanceof Point ponto) {
            return coordenadas("Point", ponto.isEmpty() ? List.of() : posicao(ponto.getCoordinate()));
        }
        if (geometria instanceof LineString linha) {
            return coordenadas("LineString", linha(linha));
        }
        if (geometria instanceof Polygon poligono) {
            return coordenadas("Polygon", poligono(poligono));
        }
        if (geometria instanceof GeometryCollection colecao) {
            var partes = new ArrayList<Object>();
            boolean multipla = colecao instanceof MultiPoint || colecao instanceof MultiLineString
                    || colecao instanceof MultiPolygon;
            for (int i = 0; i < colecao.getNumGeometries(); i++) {
                var parte = de(colecao.getGeometryN(i));
                partes.add(multipla ? parte.get("coordinates") : parte);
            }
            return Map.of("type", colecao.getGeometryType(), multipla ? "coordinates" : "geometries", partes);
        }
        throw new IllegalArgumentException("Geometria não suportada: " + geometria.getGeometryType());
    }

    private static Map<String, Object> coordenadas(String tipo, Object coordenadas) {
        return Map.of("type", tipo, "coordinates", coordenadas);
    }

    private static List<Double> posicao(Coordinate ponto) {
        return Double.isFinite(ponto.getZ())
                ? List.of(ponto.x, ponto.y, ponto.getZ()) : List.of(ponto.x, ponto.y);
    }

    private static List<List<Double>> linha(LineString linha) {
        var pontos = new ArrayList<List<Double>>();
        for (Coordinate ponto : linha.getCoordinates()) pontos.add(posicao(ponto));
        return pontos;
    }

    private static List<List<List<Double>>> poligono(Polygon poligono) {
        var aneis = new ArrayList<List<List<Double>>>();
        if (!poligono.isEmpty()) {
            aneis.add(linha(poligono.getExteriorRing()));
            for (int i = 0; i < poligono.getNumInteriorRing(); i++) {
                aneis.add(linha(poligono.getInteriorRingN(i)));
            }
        }
        return aneis;
    }
}
