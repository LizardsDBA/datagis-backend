package br.gov.sp.cps.datagis.validacao;

import java.util.Locale;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 2.6. Validador de Geometria e Recorte PR.
 *
 * <p>Responsável por:
 * <ul>
 *   <li><b>Parsing e Sintaxe:</b> converter representações textuais (WKT ou GeoJSON) em geometrias JTS;
 *       se a sintaxe estiver corrompida, retorna {@code GEOMETRIA_MALFORMADA};</li>
 *   <li><b>Obrigatoriedade:</b> se o campo for obrigatório e a geometria for nula/vazia, retorna {@code GEOMETRIA_NULA};</li>
 *   <li><b>Topologia:</b> verificar a validade topológica via JTS ({@link IsValidOp#isValid()}); se possuir
 *       auto-interseções, anéis desconexos ou auto-loops, retorna {@code GEOMETRIA_INVALIDA};</li>
 *   <li><b>Compatibilidade de Primitivo:</b> assegurar que o tipo primitivo da geometria (ex.: {@code Polygon},
 *       {@code MultiPolygon}, {@code Point}) seja compatível com a exigência da entidade destino, retornando
 *       {@code GEOMETRIA_TIPO_INCOMPATIVEL} em caso de divergência;</li>
 *   <li><b>Recorte Territorial do Paraná:</b> quando {@code validar_recorte_pr = true}, verificar se o envelope
 *       da geometria intercepta os limites geográficos oficiais do Paraná (SRID 4674), retornando
 *       {@code FORA_DO_RECORTE_TERRITORIAL} caso esteja fora;</li>
 *   <li><b>Normalização:</b> devolver a geometria formatada em WKT padronizado em caso de aprovação.</li>
 * </ul>
 */
@Component
public class ValidadorGeometria implements ValidadorCampo {

    public static final String ERRO_GEOMETRIA_NULA = "GEOMETRIA_NULA";
    public static final String ERRO_GEOMETRIA_MALFORMADA = "GEOMETRIA_MALFORMADA";
    public static final String ERRO_GEOMETRIA_INVALIDA = "GEOMETRIA_INVALIDA";
    public static final String ERRO_TIPO_INCOMPATIVEL = "GEOMETRIA_TIPO_INCOMPATIVEL";
    public static final String ERRO_FORA_RECORTE_PR = "FORA_DO_RECORTE_TERRITORIAL";

    /**
     * Bounding Box oficial com margem de segurança do estado do Paraná (SRID 4674 - SIRGAS 2000):
     * Longitude: [-55.00, -47.50] | Latitude: [-27.20, -22.00]
     */
    public static final Envelope ENVELOPE_PARANA = new Envelope(-55.00, -47.50, -27.20, -22.00);

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final WKTWriter wktWriter = new WKTWriter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        if (contexto == null) {
            return false;
        }
        return contexto.validarGeometria()
                || contexto.validarRecortePr()
                || "GEOMETRIA".equalsIgnoreCase(contexto.destinoTipo())
                || "wkt".equalsIgnoreCase(contexto.tipoDado())
                || "geojson".equalsIgnoreCase(contexto.tipoDado());
    }

    @Override
    public ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto) {
        String nomeCampo = (contexto != null && contexto.colunaOrigem() != null)
                ? contexto.colunaOrigem()
                : "geometria";

        // 1. Checagem de nulo / vazio
        if (valor == null || valor.toString().trim().isEmpty()) {
            if (contexto != null && contexto.obrigatorio()) {
                return ResultadoValidacao.erro(
                        ERRO_GEOMETRIA_NULA,
                        nomeCampo,
                        null,
                        "Geometria obrigatória não informada no payload"
                );
            }
            return ResultadoValidacao.sucesso(null);
        }

        String valorOriginal = valor.toString().trim();

        // 2. Parsing (WKT ou GeoJSON)
        Geometry geometria;
        try {
            geometria = parsearGeometria(valorOriginal);
        } catch (Exception ex) {
            return ResultadoValidacao.erro(
                    ERRO_GEOMETRIA_MALFORMADA,
                    nomeCampo,
                    valorOriginal,
                    "Geometria malformada ou formato inválido: " + ex.getMessage()
            );
        }

        if (geometria == null || geometria.isEmpty()) {
            if (contexto != null && contexto.obrigatorio()) {
                return ResultadoValidacao.erro(
                        ERRO_GEOMETRIA_NULA,
                        nomeCampo,
                        valorOriginal,
                        "Geometria informada está vazia"
                );
            }
            return ResultadoValidacao.sucesso(null);
        }

        // 3. Validação Topológica
        IsValidOp validOp = new IsValidOp(geometria);
        if (!validOp.isValid()) {
            TopologyValidationError err = validOp.getValidationError();
            String motivo = err != null
                    ? "Inconsistência topológica: " + err.getMessage() + " em " + err.getCoordinate()
                    : "Geometria topologicamente inválida";
            return ResultadoValidacao.erro(
                    ERRO_GEOMETRIA_INVALIDA,
                    nomeCampo,
                    valorOriginal,
                    motivo
            );
        }

        // 4. Validação de Primitivo Compatível
        String tipoEsperado = extrairTipoPrimitivoEsperado(contexto);
        if (tipoEsperado != null && !isPrimitivoCompativel(geometria, tipoEsperado)) {
            return ResultadoValidacao.erro(
                    ERRO_TIPO_INCOMPATIVEL,
                    nomeCampo,
                    valorOriginal,
                    "Tipo de primitiva \"" + geometria.getGeometryType()
                            + "\" incompatível com o tipo esperado \"" + tipoEsperado + "\""
            );
        }

        // 5. Validação de Recorte Territorial do Paraná
        if (contexto != null && contexto.validarRecortePr()) {
            Envelope envGeom = geometria.getEnvelopeInternal();
            if (!ENVELOPE_PARANA.intersects(envGeom)) {
                return ResultadoValidacao.erro(
                        ERRO_FORA_RECORTE_PR,
                        nomeCampo,
                        valorOriginal,
                        "Geometria localizada fora dos limites territoriais do Paraná (Envelope: "
                                + envGeom + ", Limites PR: " + ENVELOPE_PARANA + ")"
                );
            }
        }

        // 6. Sucesso — retorna WKT padronizado
        return ResultadoValidacao.sucesso(wktWriter.write(geometria));
    }

    /**
     * Converte texto em {@link Geometry} JTS. Suporta WKT e GeoJSON.
     */
    public Geometry parsearGeometria(String texto) throws ParseException {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String limpo = texto.trim();
        if (limpo.startsWith("{")) {
            return parsearGeoJson(limpo);
        }
        if (limpo.startsWith("[")) {
            return parsearJsonArrayCoordenadas(limpo);
        }
        WKTReader reader = new WKTReader(geometryFactory);
        return reader.read(limpo);
    }

    /**
     * Faz parsing de lista de coordenadas JSON, por exemplo:
     * [[-50.1, -25.2], [-50.2, -25.3], ...]
     */
    private Geometry parsearJsonArrayCoordenadas(String json) throws ParseException {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) {
                throw new ParseException("Array de coordenadas JSON vazio");
            }
            // Caso 1: Ponto único [lon, lat]
            if (root.get(0).isNumber()) {
                if (root.size() < 2) throw new ParseException("Coordenada de ponto incompleta");
                return geometryFactory.createPoint(new org.locationtech.jts.geom.Coordinate(
                        root.get(0).doubleValue(), root.get(1).doubleValue()));
            }
            // Caso 2: Array de pares [[lon, lat], [lon, lat], ...]
            if (root.get(0).isArray() && root.get(0).size() >= 2 && root.get(0).get(0).isNumber()) {
                org.locationtech.jts.geom.Coordinate[] ring = extrairArrayCoordenadas(root);
                if (ring.length >= 3 && !ring[0].equals2D(ring[ring.length - 1])) {
                    org.locationtech.jts.geom.Coordinate[] fechado = new org.locationtech.jts.geom.Coordinate[ring.length + 1];
                    System.arraycopy(ring, 0, fechado, 0, ring.length);
                    fechado[fechado.length - 1] = ring[0].copy();
                    ring = fechado;
                }
                return geometryFactory.createPolygon(geometryFactory.createLinearRing(ring));
            }
            // Caso 3: Array aninhado (anéis ou múltiplos polígonos)
            if (root.get(0).isArray() && root.get(0).size() > 0 && root.get(0).get(0).isArray()) {
                if (root.get(0).get(0).get(0).isNumber()) {
                    JsonNode exterior = root.get(0);
                    org.locationtech.jts.geom.Coordinate[] ring = extrairArrayCoordenadas(exterior);
                    if (ring.length >= 3 && !ring[0].equals2D(ring[ring.length - 1])) {
                        org.locationtech.jts.geom.Coordinate[] fechado = new org.locationtech.jts.geom.Coordinate[ring.length + 1];
                        System.arraycopy(ring, 0, fechado, 0, ring.length);
                        fechado[fechado.length - 1] = ring[0].copy();
                        ring = fechado;
                    }
                    return geometryFactory.createPolygon(geometryFactory.createLinearRing(ring));
                }
                return converterCoordenadasGeoJson("MULTIPOLYGON", root);
            }
            throw new ParseException("Formato de coordenadas JSON não reconhecido");
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("Erro ao processar lista de coordenadas JSON: " + e.getMessage());
        }
    }

    /**
     * Faz parsing de GeoJSON simples para geometria JTS.
     */
    private Geometry parsearGeoJson(String json) throws ParseException {
        try {
            JsonNode node = objectMapper.readTree(json);
            String tipo = node.path("type").asString();
            if (tipo == null || tipo.isBlank()) {
                throw new ParseException("GeoJSON sem campo 'type'");
            }
            // Suporta GeoJSON Feature ou Geometry direta
            JsonNode geomNode = "Feature".equalsIgnoreCase(tipo) ? node.path("geometry") : node;
            String geomType = geomNode.path("type").asString();
            JsonNode coords = geomNode.path("coordinates");

            if (geomType == null || geomType.isBlank() || coords.isMissingNode() || coords.isNull()) {
                throw new ParseException("GeoJSON sem coordenadas válidas");
            }

            // Mapeia para WKT via WKTWriter do JTS após montar coordenadas
            // Para robustez direta, WKTReader pode ler WKT gerado ou montamos primitivas
            return converterCoordenadasGeoJson(geomType, coords);
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("Erro ao processar GeoJSON: " + e.getMessage());
        }
    }

    private Geometry converterCoordenadasGeoJson(String tipo, JsonNode coords) throws ParseException {
        // Suporte a Point, Polygon e MultiPolygon básicos
        switch (tipo.toUpperCase(Locale.ROOT)) {
            case "POINT" -> {
                double x = coords.get(0).doubleValue();
                double y = coords.get(1).doubleValue();
                return geometryFactory.createPoint(new org.locationtech.jts.geom.Coordinate(x, y));
            }
            case "POLYGON" -> {
                JsonNode ringNode = coords.get(0);
                org.locationtech.jts.geom.Coordinate[] ring = extrairArrayCoordenadas(ringNode);
                return geometryFactory.createPolygon(geometryFactory.createLinearRing(ring));
            }
            case "MULTIPOLYGON" -> {
                org.locationtech.jts.geom.Polygon[] poligonos = new org.locationtech.jts.geom.Polygon[coords.size()];
                for (int i = 0; i < coords.size(); i++) {
                    JsonNode polyCoords = coords.get(i);
                    org.locationtech.jts.geom.Coordinate[] ring = extrairArrayCoordenadas(polyCoords.get(0));
                    poligonos[i] = geometryFactory.createPolygon(geometryFactory.createLinearRing(ring));
                }
                return geometryFactory.createMultiPolygon(poligonos);
            }
            default -> throw new ParseException("Tipo GeoJSON não suportado: " + tipo);
        }
    }

    private org.locationtech.jts.geom.Coordinate[] extrairArrayCoordenadas(JsonNode ringNode) {
        org.locationtech.jts.geom.Coordinate[] coords = new org.locationtech.jts.geom.Coordinate[ringNode.size()];
        for (int i = 0; i < ringNode.size(); i++) {
            JsonNode c = ringNode.get(i);
            coords[i] = new org.locationtech.jts.geom.Coordinate(c.get(0).doubleValue(), c.get(1).doubleValue());
        }
        return coords;
    }

    /**
     * Determina se a geometria atende ao primitivo esperado.
     */
    public boolean isPrimitivoCompativel(Geometry geom, String tipoEsperado) {
        if (geom == null || tipoEsperado == null) {
            return true;
        }
        String tipoReal = geom.getGeometryType().toUpperCase(Locale.ROOT);
        String esperado = tipoEsperado.toUpperCase(Locale.ROOT);

        if (esperado.contains("POLYGON")) {
            return "POLYGON".equals(tipoReal) || "MULTIPOLYGON".equals(tipoReal);
        }
        if (esperado.contains("POINT")) {
            return "POINT".equals(tipoReal) || "MULTIPOINT".equals(tipoReal);
        }
        if (esperado.contains("LINE")) {
            return "LINESTRING".equals(tipoReal) || "MULTILINESTRING".equals(tipoReal);
        }
        return tipoReal.equalsIgnoreCase(esperado);
    }

    /**
     * Extrai o primitivo esperado das regras extras ou tipo de dado da coluna.
     */
    public String extrairTipoPrimitivoEsperado(ContextoValidacaoCampo contexto) {
        if (contexto == null) {
            return null;
        }
        if (contexto.regrasExtras() != null && !contexto.regrasExtras().isBlank()) {
            String extras = contexto.regrasExtras().toUpperCase(Locale.ROOT);
            if (extras.contains("MULTIPOLYGON")) return "MULTIPOLYGON";
            if (extras.contains("POLYGON") || extras.contains("POLIGONAL")) return "POLYGON";
            if (extras.contains("POINT") || extras.contains("PONTO")) return "POINT";
            if (extras.contains("LINESTRING") || extras.contains("LINHA")) return "LINESTRING";
        }
        if (contexto.tipoDado() != null) {
            String td = contexto.tipoDado().toUpperCase(Locale.ROOT);
            if (td.contains("MULTIPOLYGON")) return "MULTIPOLYGON";
            if (td.contains("POLYGON") || td.contains("POLIGONAL")) return "POLYGON";
            if (td.contains("POINT") || td.contains("PONTO")) return "POINT";
            if (td.contains("LINESTRING") || td.contains("LINHA")) return "LINESTRING";
        }
        return null;
    }
}
