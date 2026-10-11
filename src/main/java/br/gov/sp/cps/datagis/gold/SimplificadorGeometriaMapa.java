package br.gov.sp.cps.datagis.gold;

import org.locationtech.jts.geom.Geometry;
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
        try {
            Geometry geometria = wktReader.read(wkt);
            Geometry simplificada = TopologyPreservingSimplifier.simplify(geometria, TOLERANCIA_GRAUS);
            return wktWriter.write(simplificada);
        } catch (ParseException e) {
            throw new IllegalArgumentException("WKT inválido: " + e.getMessage(), e);
        }
    }
}
