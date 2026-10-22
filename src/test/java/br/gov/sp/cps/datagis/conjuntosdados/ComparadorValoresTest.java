package br.gov.sp.cps.datagis.conjuntosdados;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ComparadorValoresTest {

    @Test
    void numeroEmTextoNoBronzeIgualaNumberDaSilver() {
        assertThat(ComparadorValores.iguais("12.50", new BigDecimal("12.5"))).isTrue();
        assertThat(ComparadorValores.iguais(4106902, "4106902")).isTrue();
    }

    @Test
    void numerosDiferentesNaoSaoIguais() {
        assertThat(ComparadorValores.iguais("12.5", new BigDecimal("12.6"))).isFalse();
    }

    @Test
    void textoIgnoraCaixaAcentoEEspacosNasPontas() {
        assertThat(ComparadorValores.iguais(" Análise Pendente ", "analise pendente")).isTrue();
        assertThat(ComparadorValores.iguais("AT", "Ativo")).isFalse();
    }

    @Test
    void nuloEVazioContamComoMesmaCoisa() {
        assertThat(ComparadorValores.iguais(null, "")).isTrue();
        assertThat(ComparadorValores.iguais("  ", null)).isTrue();
        assertThat(ComparadorValores.iguais(null, "x")).isFalse();
    }

    @Test
    void geometriaIguaisComPrecisaoDeTextoDiferente() {
        assertThat(ComparadorValores.geometriasIguais(
                "POLYGON ((0 0, 1 0, 1 1, 0 0))",
                "POLYGON((0.0 0.0,1.0 0.0,1.0 1.0,0.0 0.0))")).isTrue();
    }

    @Test
    void multipolygonDeUmPoligonoEIgualAoPolygon() {
        assertThat(ComparadorValores.geometriasIguais(
                "MULTIPOLYGON (((0 0, 1 0, 1 1, 0 0)))",
                "POLYGON ((0 0, 1 0, 1 1, 0 0))")).isTrue();
    }

    @Test
    void multipolygonDeDoisPoligonosNaoEIgualAUmPolygon() {
        assertThat(ComparadorValores.geometriasIguais(
                "MULTIPOLYGON (((0 0, 1 0, 1 1, 0 0)), ((5 5, 6 5, 6 6, 5 5)))",
                "POLYGON ((0 0, 1 0, 1 1, 0 0))")).isFalse();
    }

    @Test
    void geometriaComVerticeDiferenteNaoEIgual() {
        assertThat(ComparadorValores.geometriasIguais(
                "POLYGON ((0 0, 1 0, 1 1, 0 0))",
                "POLYGON ((0 0, 2 0, 2 2, 0 0))")).isFalse();
    }

    @Test
    void geometriaAusenteDeUmLadoSoEIgualSeOutroTambemNaoTem() {
        assertThat(ComparadorValores.geometriasIguais(null, null)).isTrue();
        assertThat(ComparadorValores.geometriasIguais("POINT (1 1)", null)).isFalse();
    }

    @Test
    void wktInvalidoCaiParaComparacaoDeTexto() {
        assertThat(ComparadorValores.geometriasIguais("isso nao e wkt", "isso nao e wkt")).isTrue();
        assertThat(ComparadorValores.geometriasIguais("isso nao e wkt", "POINT (1 1)")).isFalse();
    }
}
