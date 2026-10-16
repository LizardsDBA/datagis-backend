package br.gov.sp.cps.datagis.conjuntosdados;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

/**
 * Regras de igualdade usadas na comparação entre camadas. Cada camada guarda o mesmo
 * dado num tipo diferente (Bronze é texto do JSON, Silver/Gold são {@code NUMBER}), então
 * comparar por {@code equals} acusaria diferença onde só mudou a representação.
 */
final class ComparadorValores {

    /** ~1e-9 grau, bem abaixo da precisão do SIRGAS 2000; absorve só o arredondamento do WKT do Oracle. */
    private static final double TOLERANCIA_GEOMETRIA = 1e-9;

    private ComparadorValores() {
    }

    static boolean iguais(Object origem, Object destino) {
        if (vazio(origem) && vazio(destino)) return true;
        if (vazio(origem) || vazio(destino)) return false;

        BigDecimal numeroOrigem = comoNumero(origem);
        BigDecimal numeroDestino = comoNumero(destino);
        if (numeroOrigem != null && numeroDestino != null) {
            return numeroOrigem.compareTo(numeroDestino) == 0;
        }
        return normalizarTexto(origem).equals(normalizarTexto(destino));
    }

    /** Compara geometrias por forma (não por texto); se algum WKT não parsear, cai pra igualdade de texto. */
    static boolean geometriasIguais(String wktOrigem, String wktDestino) {
        if (vazio(wktOrigem) && vazio(wktDestino)) return true;
        if (vazio(wktOrigem) || vazio(wktDestino)) return false;
        try {
            WKTReader leitor = new WKTReader();
            Geometry origem = desembrulhar(leitor.read(wktOrigem));
            Geometry destino = desembrulhar(leitor.read(wktDestino));
            return origem.equalsExact(destino, TOLERANCIA_GEOMETRIA)
                    || origem.norm().equalsExact(destino.norm(), TOLERANCIA_GEOMETRIA);
        } catch (Exception e) {
            return wktOrigem.trim().equals(wktDestino.trim());
        }
    }

    /**
     * MULTIPOLYGON de um polígono só é a mesma forma que o POLYGON: a Silver grava o
     * polígono simples e o Bronze guarda o MultiPolygon que veio da fonte. Sem isso o
     * diff acusaria diferença onde só mudou o tipo do contêiner.
     */
    private static Geometry desembrulhar(Geometry geometria) {
        return geometria.getNumGeometries() == 1 && geometria.getGeometryType().startsWith("Multi")
                ? geometria.getGeometryN(0)
                : geometria;
    }

    private static boolean vazio(Object valor) {
        return valor == null || valor.toString().isBlank();
    }

    private static BigDecimal comoNumero(Object valor) {
        if (valor instanceof Number numero) {
            return new BigDecimal(numero.toString());
        }
        try {
            return new BigDecimal(valor.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizarTexto(Object valor) {
        String semAcento = Normalizer.normalize(valor.toString().trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT);
    }
}
