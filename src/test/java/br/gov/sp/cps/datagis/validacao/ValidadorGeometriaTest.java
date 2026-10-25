package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidadorGeometriaTest {

    private final ValidadorGeometria validador = new ValidadorGeometria();

    // -------------------------------------------------------------------------
    // suporta()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve suportar coluna com flag validarGeometria = true")
    void deveSuportarPorFlagGeometria() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @Test
    @DisplayName("Não deve suportar apenas por flag validarRecortePr")
    void naoDeveSuportarPorFlagRecortePr() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("coordenadas")
                .validarRecortePr(true)
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GEOMETRIA", "geometria", "Geometria"})
    @DisplayName("Deve suportar quando destinoTipo for GEOMETRIA")
    void deveSuportarPorDestinoTipo(String destinoTipo) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geom")
                .destinoTipo(destinoTipo)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wkt", "WKT", "geojson", "GeoJSON"})
    @DisplayName("Deve suportar quando tipoDado for WKT ou GeoJSON")
    void deveSuportarPorTipoDado(String tipoDado) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("wkt_geom")
                .tipoDado(tipoDado)
                .build();
        assertThat(validador.suporta(ctx)).isTrue();
    }

    @Test
    @DisplayName("Não deve suportar quando nenhuma flag ou tipo geométrico estiver configurado")
    void naoDeveSuportarSemConfiguracao() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("nome")
                .tipoDado("texto")
                .build();
        assertThat(validador.suporta(ctx)).isFalse();
    }

    @Test
    @DisplayName("Não deve suportar contexto nulo")
    void naoDeveSuportarContextoNulo() {
        assertThat(validador.suporta(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // validar() — Sucesso
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve aprovar polígono WKT válido dentro do Paraná")
    void deveAprovarPoligonoWktValido() {
        // Polígono em Curitiba/PR
        String wkt = "POLYGON ((-49.30 -25.50, -49.20 -25.50, -49.20 -25.40, -49.30 -25.40, -49.30 -25.50))";
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .validarRecortePr(true)
                .build();

        ResultadoValidacao resultado = validador.validar(wkt, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).asString().startsWith("POLYGON");
        assertThat(resultado.tipoErro()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar ponto WKT válido em Maringá/PR")
    void deveAprovarPontoWktValido() {
        String wkt = "POINT (-51.93 -23.42)";
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("ponto_foco")
                .validarGeometria(true)
                .validarRecortePr(true)
                .build();

        ResultadoValidacao resultado = validador.validar(wkt, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).asString().contains("-51.93");
    }

    @Test
    @DisplayName("Deve aprovar GeoJSON válido")
    void deveAprovarGeoJsonValido() {
        String geoJson = """
                {
                  "type": "Polygon",
                  "coordinates": [
                    [[-49.3, -25.5], [-49.2, -25.5], [-49.2, -25.4], [-49.3, -25.4], [-49.3, -25.5]]
                  ]
                }
                """;
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geojson_geom")
                .validarGeometria(true)
                .validarRecortePr(true)
                .build();

        ResultadoValidacao resultado = validador.validar(geoJson, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).asString().startsWith("POLYGON");
    }

    // -------------------------------------------------------------------------
    // validar() — Erro: GEOMETRIA_MALFORMADA
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "POLYGON (((1 2, 3 4)))", // anel sem fechar e com menos de 4 pontos
            "INVALID_WKT_TEXT",
            "POINT (abc def)",
            "{\"type\": \"Polygon\", \"coordinates\": \"invalido\"}"
    })
    @DisplayName("Deve rejeitar sintaxe inválida com erro GEOMETRIA_MALFORMADA")
    void deveRejeitarGeometriaMalformada(String wktInvalido) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .build();

        ResultadoValidacao resultado = validador.validar(wktInvalido, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("GEOMETRIA_MALFORMADA");
        assertThat(resultado.campoProblema()).isEqualTo("geometria");
        assertThat(resultado.motivoRejeicao()).contains("Geometria malformada");
    }

    // -------------------------------------------------------------------------
    // validar() — Erro: GEOMETRIA_INVALIDA (Topologia JTS)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve reparar polígono com auto-interseção via buffer(0) em vez de rejeitar")
    void deveRepararAutoIntersecao() {
        // Achado real no PRODES: 5.740 polígonos de desmatamento, 100% rejeitados por
        // auto-interseção simples - buffer(0) é o truque padrão de GIS pra reconstruir
        // uma geometria válida a partir de uma auto-interseccionada, sem perder o dado.
        // Polígono em formato de 'laço/borboleta' (auto-interseção no centro).
        String bowtie = "POLYGON ((-49.3 -25.5, -49.2 -25.4, -49.2 -25.5, -49.3 -25.4, -49.3 -25.5))";
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .build();

        ResultadoValidacao resultado = validador.validar(bowtie, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // validar() — Erro: GEOMETRIA_TIPO_INCOMPATIVEL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve rejeitar Point quando o contexto exigir Polygon")
    void deveRejeitarPontoOndeEsperaPoligono() {
        String pointWkt = "POINT (-49.27 -25.43)";
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria_imovel")
                .validarGeometria(true)
                .tipoDado("polygon")
                .build();

        ResultadoValidacao resultado = validador.validar(pointWkt, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("GEOMETRIA_TIPO_INCOMPATIVEL");
        assertThat(resultado.campoProblema()).isEqualTo("geometria_imovel");
        assertThat(resultado.motivoRejeicao()).contains("incompatível com o tipo esperado");
    }

    @Test
    @DisplayName("Deve rejeitar Polygon quando regrasExtras exigir Point")
    void deveRejeitarPoligonoOndeEsperaPonto() {
        String polyWkt = "POLYGON ((-49.3 -25.5, -49.2 -25.5, -49.2 -25.4, -49.3 -25.4, -49.3 -25.5))";
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria_foco")
                .validarGeometria(true)
                .regrasExtras("{\"tipo_primitivo\": \"POINT\"}")
                .build();

        ResultadoValidacao resultado = validador.validar(polyWkt, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("GEOMETRIA_TIPO_INCOMPATIVEL");
    }

    // -------------------------------------------------------------------------
    // validar() — Remoção do erro FORA_DO_RECORTE_TERRITORIAL
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "POINT (-46.63 -23.55)", // São Paulo capital (SP)
            "POINT (-43.17 -22.90)", // Rio de Janeiro (RJ)
            "POINT (-47.88 -15.79)", // Brasília (DF)
            "POINT (-51.22 -30.03)", // Porto Alegre (RS)
            "POINT (-60.02 -3.11)",  // Manaus (AM)
            "POLYGON ((-46.7 -23.6, -46.6 -23.6, -46.6 -23.5, -46.7 -23.5, -46.7 -23.6))" // Polígono em SP
    })
    @DisplayName("Não deve rejeitar geometria fora dos limites territoriais do Paraná após remoção da restrição")
    void naoDeveRejeitarForaDoRecorteParana(String wktFora) {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("localizacao")
                .validarGeometria(true)
                .validarRecortePr(true)
                .build();

        ResultadoValidacao resultado = validador.validar(wktFora, ctx);

        assertThat(resultado.isValido()).isTrue();
    }

    // -------------------------------------------------------------------------
    // validar() — Erro: GEOMETRIA_NULA
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve rejeitar geometria nula quando o campo for obrigatório com erro GEOMETRIA_NULA")
    void deveRejeitarGeometriaNulaObrigatoria() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .obrigatorio(true)
                .build();

        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isFalse();
        assertThat(resultado.tipoErro()).isEqualTo("GEOMETRIA_NULA");
    }

    @Test
    @DisplayName("Deve aprovar geometria nula quando o campo não for obrigatório")
    void deveAprovarGeometriaNulaOpcional() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .obrigatorio(false)
                .build();

        ResultadoValidacao resultado = validador.validar(null, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNull();
    }

    @Test
    @DisplayName("Deve aprovar e converter lista de coordenadas JSON em formato [[x, y], ...]")
    void deveAprovarListaCoordenadasJson() {
        ContextoValidacaoCampo ctx = ContextoValidacaoCampo.builder()
                .colunaOrigem("geometria")
                .validarGeometria(true)
                .validarRecortePr(true)
                .build();

        String jsonCoords = """
                [
                  [-51.4, -24.2],
                  [-51.3, -24.2],
                  [-51.3, -24.1],
                  [-51.4, -24.1],
                  [-51.4, -24.2]
                ]
                """;

        ResultadoValidacao resultado = validador.validar(jsonCoords, ctx);

        assertThat(resultado.isValido()).isTrue();
        assertThat(resultado.valorTransformado()).isNotNull();
        assertThat(resultado.valorTransformado().toString()).startsWith("POLYGON");
    }
}
