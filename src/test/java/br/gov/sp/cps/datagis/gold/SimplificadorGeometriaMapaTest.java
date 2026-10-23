package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;

class SimplificadorGeometriaMapaTest {

    private final SimplificadorGeometriaMapa simplificador = new SimplificadorGeometriaMapa();

    // Anel externo no sentido horário (como o CAR manda) com um buraco no anti-horário:
    // os dois ao contrário do que o Oracle exige.
    private static final String POLIGONO_INVERTIDO = """
            POLYGON ((-50 -25, -50 -24, -49 -24, -49 -25, -50 -25),
                     (-49.8 -24.8, -49.2 -24.8, -49.2 -24.2, -49.8 -24.2, -49.8 -24.8))
            """;

    @Test
    void orientaAnelExternoNoSentidoAntiHorario() throws Exception {
        Polygon resultado = (Polygon) ler(simplificador.simplificar(POLIGONO_INVERTIDO));

        assertThat(Orientation.isCCW(resultado.getExteriorRing().getCoordinateSequence())).isTrue();
    }

    @Test
    void orientaBuracoNoSentidoHorario() throws Exception {
        Polygon resultado = (Polygon) ler(simplificador.simplificar(POLIGONO_INVERTIDO));

        assertThat(resultado.getNumInteriorRing()).isEqualTo(1);
        assertThat(Orientation.isCCW(resultado.getInteriorRingN(0).getCoordinateSequence())).isFalse();
    }

    @Test
    void naoMudaAreaNemFormaAoReorientar() throws Exception {
        Geometry original = ler(POLIGONO_INVERTIDO);
        Geometry resultado = ler(simplificador.simplificar(POLIGONO_INVERTIDO));

        assertThat(resultado.getArea()).isEqualTo(original.getArea());
        assertThat(resultado.equalsTopo(original)).isTrue();
    }

    @Test
    void orientaCadaParteDoMultiPolygon() throws Exception {
        String multi = """
                MULTIPOLYGON (((-50 -25, -50 -24, -49 -24, -49 -25, -50 -25)),
                              ((-48 -25, -47 -25, -47 -24, -48 -24, -48 -25)))
                """;

        MultiPolygon resultado = (MultiPolygon) ler(simplificador.simplificar(multi));

        for (int i = 0; i < resultado.getNumGeometries(); i++) {
            Polygon parte = (Polygon) resultado.getGeometryN(i);
            assertThat(Orientation.isCCW(parte.getExteriorRing().getCoordinateSequence())).isTrue();
        }
    }

    @Test
    void pontoPassaSemAlteracao() throws Exception {
        Geometry resultado = ler(simplificador.simplificar("POINT (-50 -25)"));

        assertThat(resultado.equalsExact(ler("POINT (-50 -25)"))).isTrue();
    }

    @Test
    void contornoExternoDescartaOsBuracos() throws Exception {
        Polygon resultado = (Polygon) ler(simplificador.simplificarContornoExterno(POLIGONO_INVERTIDO, 0.0005));

        assertThat(resultado.getNumInteriorRing()).isZero();
        assertThat(Orientation.isCCW(resultado.getExteriorRing().getCoordinateSequence())).isTrue();
        assertThat(resultado.getArea()).isEqualTo(1.0);
    }

    @Test
    void toleranciaMaiorRemoveMaisVertices() {
        // Serrilhado de 0,0001° (~11 m) ao longo de um lado: some com 50 m, fica com 5 m.
        StringBuilder lado = new StringBuilder();
        for (int i = 0; i <= 100; i++) {
            lado.append(-50 + i * 0.01).append(' ').append(i % 2 == 0 ? -25 : -24.9999).append(", ");
        }
        String serrilhado = "POLYGON ((" + lado + "-49 -24, -50 -24, -50 -25))";

        Geometry padrao = lerSemErro(simplificador.simplificar(serrilhado));
        Geometry grosso = lerSemErro(simplificador.simplificar(serrilhado,
                GoldPublicacaoService.TOLERANCIA_LIMITE_PR_GRAUS));

        assertThat(grosso.getNumPoints()).isLessThan(padrao.getNumPoints());
    }

    @Test
    void wktInvalidoViraIllegalArgumentException() {
        assertThatThrownBy(() -> simplificador.simplificar("POLYGON ((nao e wkt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WKT inválido");
    }

    private static Geometry ler(String wkt) throws Exception {
        return new WKTReader().read(wkt);
    }

    private static Geometry lerSemErro(String wkt) {
        try {
            return ler(wkt);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
