package br.gov.sp.cps.datagis.gold;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.springframework.stereotype.Component;

/**
 * Simplifica geometria (Douglas-Peucker, preservando topologia) pra publicação de
 * camadas de mapa (bloco 2 da Gold) - JTS puro, roda no heap da JVM, não no PGA do
 * Oracle.
 *
 * <p>Substitui {@code SDO_UTIL.SIMPLIFY} (PL/SQL) - achado ao vivo publicando
 * DESMATAMENTO contra o PRODES real: algumas geometrias (ex.: um polígono com 634 mil
 * ordenadas) fazem {@code SDO_UTIL.SIMPLIFY} sozinho, numa sessão isolada, estourar
 * {@code ORA-04036} (PGA_AGGREGATE_LIMIT de 8GB) - não é demora, é a própria operação
 * excedendo o teto de memória do banco. O algoritmo do JTS opera direto em array de
 * coordenadas (sem os custos internos da implementação do Oracle) e usa a memória do
 * container do backend (heap da JVM, {@code JAVA_TOOL_OPTIONS=-Xmx8g}), separada do
 * PGA do Oracle - decisão de negócio: nenhum registro pode ser descartado por
 * limitação de memória (LIZARDS-50), então mover o cálculo pra onde ele cabe é a
 * correção de verdade, não um workaround.
 */
@Component
public class SimplificadorGeometriaMapa {

    private final WKTReader wktReader = new WKTReader();
    private final WKTWriter wktWriter = new WKTWriter();

    /** Tolerância de generalização visual (~5m) - mesmo valor numérico já usado no
     *  {@code SDO_UTIL.SIMPLIFY} anterior (V044, c_tolerancia_mapa), pra manter o
     *  mesmo nível de detalhe no mapa. */
    public static final double TOLERANCIA_GRAUS = 0.00005;

    public String simplificar(String wkt) {
        return simplificar(wkt, TOLERANCIA_GRAUS);
    }

    public String simplificar(String wkt, double toleranciaGraus) {
        Geometry simplificada = TopologyPreservingSimplifier.simplify(ler(wkt), toleranciaGraus);
        return wktWriter.write(orientarParaOracle(simplificada));
    }

    /**
     * Só o contorno externo, sem os buracos, com tolerância própria - pra camada que
     * é só moldura do mapa (contorno do Paraná, LIZARDS-35). A união dos 399
     * municípios sai com ~9,8 mil buracos que não existem de verdade: frestas entre
     * municípios vizinhos, porque a malha do IBGE já foi simplificada a 5 m na V037
     * e as divisas não encaixam perfeitamente (o maior tem 2,6 ha; somados, 1,5 km²
     * de 199.891 km²). Com eles o simplificador não consegue reduzir nada (45 mil
     * vértices); sem eles, ~6 mil a 50 m, com a mesma área.
     */
    public String simplificarContornoExterno(String wkt, double toleranciaGraus) {
        Geometry geometria = ler(wkt);
        GeometryFactory fabrica = geometria.getFactory();
        Polygon[] contornos = new Polygon[geometria.getNumGeometries()];
        for (int i = 0; i < contornos.length; i++) {
            Polygon parte = (Polygon) geometria.getGeometryN(i);
            contornos[i] = fabrica.createPolygon(parte.getExteriorRing().getCoordinateSequence());
        }
        Geometry semBuracos = contornos.length == 1 ? contornos[0] : fabrica.createMultiPolygon(contornos);
        Geometry simplificada = TopologyPreservingSimplifier.simplify(semBuracos, toleranciaGraus);
        return wktWriter.write(orientarParaOracle(simplificada));
    }

    /**
     * O Oracle exige o anel externo no sentido anti-horário e os buracos no horário -
     * fora disso a geometria é inválida (ORA-13367) e o SDO_RELATE não tem resultado
     * garantido. O JTS não impõe sentido nenhum e o {@code SDO_GEOMETRY(wkt)} não
     * corrige: achado ao publicar o mapa de cobertura, 2.101 dos 4.611 imóveis da Gold
     * estavam com o anel invertido (o CAR manda no sentido horário). Ponto e linha
     * passam direto.
     *
     * <p><b>Reusado fora da publicação de mapa</b> — {@code ValidadorGeometria}
     * (pacote {@code validacao}) chama este método na gravação Bronze→Silver, achado
     * na investigação do ISAP=0% (docs/publicacao-gold-paralela.md §12): o JTS não
     * considera anel invertido uma invalidade (por isso passava batido pelo
     * {@code IsValidOp} da validação), só o Oracle rejeita — e sem lançar erro,
     * {@code SDO_GEOM.SDO_INTERSECTION} contra essa geometria calcula uma área
     * "lixo" (perto de zero) em vez de falhar, zerando o indicador silenciosamente.
     */
    public static Geometry orientarParaOracle(Geometry geometria) {
        GeometryFactory fabrica = geometria.getFactory();
        if (geometria instanceof Polygon poligono) {
            return orientar(poligono, fabrica);
        }
        if (geometria instanceof MultiPolygon multi) {
            Polygon[] partes = new Polygon[multi.getNumGeometries()];
            for (int i = 0; i < partes.length; i++) {
                partes[i] = orientar((Polygon) multi.getGeometryN(i), fabrica);
            }
            return fabrica.createMultiPolygon(partes);
        }
        return geometria;
    }

    private static Polygon orientar(Polygon poligono, GeometryFactory fabrica) {
        if (poligono.isEmpty()) {
            return poligono;
        }
        LinearRing externo = poligono.getExteriorRing();
        if (!Orientation.isCCW(externo.getCoordinateSequence())) {
            externo = externo.reverse();
        }
        LinearRing[] buracos = new LinearRing[poligono.getNumInteriorRing()];
        for (int i = 0; i < buracos.length; i++) {
            LinearRing buraco = poligono.getInteriorRingN(i);
            buracos[i] = Orientation.isCCW(buraco.getCoordinateSequence()) ? buraco.reverse() : buraco;
        }
        return fabrica.createPolygon(externo, buracos);
    }

    /**
     * Decompõe um {@code MultiPolygon} em polígonos individuais e simplifica cada um
     * separadamente — pra uso de cálculo (não de mapa), onde o problema não é só
     * vértice-por-polígono, mas a geometria inteira ser indexada como 1 feição só.
     * Achado ao vivo (docs/publicacao-gold-paralela.md §3.2): 1 polígono do PRODES
     * (ano 2000, manchas de desmatamento acumuladas) é um MultiPolygon fragmentado com
     * MBR de ~180×130 km — maior que o município de Maringá inteiro. Como índice
     * espacial é por feição (não por sub-elemento), toda consulta
     * {@code SDO_RELATE} contra QUALQUER imóvel cujo bounding box caia dentro desse
     * MBR gigante precisa do check exato contra a geometria inteira (123 mil vértices,
     * mesmo simplificada) pra só então descobrir que não intersecciona de verdade —
     * caro mesmo sem erro, sem geometria inválida, só por desenho de índice. Separar
     * em features pequenas, cada uma com seu próprio MBR apertado, devolve a seletividade
     * real ao índice: a maioria dos imóveis nem chega a pedir o check exato.
     */
    public List<String> decomporEsimplificar(String wkt, double toleranciaGraus) {
        Geometry geometria = ler(wkt);
        List<String> partes = new ArrayList<>();
        int n = geometria.getNumGeometries();
        for (int i = 0; i < n; i++) {
            Geometry parte = geometria.getGeometryN(i);
            if (parte.isEmpty()) {
                continue;
            }
            Geometry simplificada = TopologyPreservingSimplifier.simplify(parte, toleranciaGraus);
            if (simplificada.isEmpty()) {
                continue;
            }
            partes.add(wktWriter.write(orientarParaOracle(simplificada)));
        }
        return partes;
    }

    private Geometry ler(String wkt) {
        try {
            return wktReader.read(wkt);
        } catch (ParseException e) {
            throw new IllegalArgumentException("WKT inválido: " + e.getMessage(), e);
        }
    }
}
