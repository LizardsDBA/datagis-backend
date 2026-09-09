package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingestão genérica pra qualquer fonte cujo {@code formatoEsperado} seja
 * {@code GEOJSON} — não sabe nada sobre CAR, PRODES, ICMBio ou qualquer fonte
 * específica. Isso inclui, sem precisar de nenhum código a mais: qualquer WFS pedido
 * com {@code outputFormat=application/json}, e qualquer ArcGIS REST (o geoportal do
 * Paraná, por exemplo) pedido com {@code f=geojson} — o ArcGIS já sabe devolver GeoJSON
 * padrão, então a mesma lógica atende os dois provedores (ver
 * docs/fontes-de-dados-v2.md).
 */
@Service
public class IngestaoGeoJsonExecutor implements IngestaoExecutor {

    private static final String FORMATO = "GEOJSON";

    private final HttpFetchClient httpFetchClient;
    private final GeoJsonWktMapper wktMapper;
    private final LoteIngestaoWriter writer;
    private final ObjectMapper objectMapper;

    public IngestaoGeoJsonExecutor(
            HttpFetchClient httpFetchClient,
            GeoJsonWktMapper wktMapper,
            LoteIngestaoWriter writer,
            ObjectMapper objectMapper) {
        this.httpFetchClient = httpFetchClient;
        this.wktMapper = wktMapper;
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean suportar(FonteDado fonte) {
        return FORMATO.equalsIgnoreCase(fonte.getFormatoEsperado());
    }

    @Override
    public LoteIngestao executar(FonteDado fonte) {
        String corpoBruto = httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem());
        JsonNode featureCollection = lerJson(corpoBruto, fonte);
        JsonNode features = featureCollection.path("features");

        // Hash só das features, não da resposta inteira — vários provedores mandam
        // timestamp/metadado que muda a cada chamada mesmo com o dado igual
        // (CLAUDE.md, RN018).
        String conteudoParaHash = escreverJson(features);

        List<RegistroPreparado> registros = new ArrayList<>();
        for (JsonNode feature : features) {
            registros.add(new RegistroPreparado(
                    escreverJson(feature.path("properties")),
                    wktMapper.paraWkt(feature.path("geometry"))));
        }

        return writer.gravar(fonte, conteudoParaHash, registros);
    }

    private JsonNode lerJson(String texto, FonteDado fonte) {
        try {
            return objectMapper.readTree(texto);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Resposta de \"" + fonte.getNome() + "\" não é um JSON válido", e);
        }
    }

    private String escreverJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar trecho do GeoJSON", e);
        }
    }
}
