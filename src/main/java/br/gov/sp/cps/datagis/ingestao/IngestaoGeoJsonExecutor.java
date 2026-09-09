package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Ingestão genérica pra qualquer fonte cujo {@code formatoEsperado} seja
 * {@code GEOJSON} — não sabe nada sobre CAR, PRODES, ICMBio ou qualquer fonte
 * específica. Isso inclui, sem precisar de nenhum código a mais: qualquer WFS pedido
 * com {@code outputFormat=application/json}, e qualquer ArcGIS REST (o geoportal do
 * Paraná, por exemplo) pedido com {@code f=geojson} — o ArcGIS já sabe devolver GeoJSON
 * padrão, então a mesma lógica atende os dois provedores (ver
 * docs/fontes-de-dados-v2.md).
 *
 * <p><b>Paginação:</b> busca TODAS as páginas antes de gravar, via
 * {@link PaginadorUrlGeoJson} — nunca fica limitado ao tamanho de página padrão do
 * servidor (ex.: ArcGIS corta em ~2000 por padrão; isso já causou perda real de
 * registro nos Embargos IBAMA do IAT/PR antes desta classe existir). Continua fazendo
 * uma chamada só pra fontes cuja família de API não reconhecemos (ex.: download direto
 * de um arquivo estático GeoJSON) — não tem o que paginar nesse caso.
 *
 * <p><b>Cuidado conhecido, não resolvido aqui:</b> o hash de "mudou desde a última
 * execução" (RN018) é calculado sobre a concatenação de todas as páginas, na ordem em
 * que chegaram. Se o servidor não garantir ordem estável entre chamadas (sem
 * {@code sortBy}/{@code orderByFields}), o mesmo dado poderia, em teoria, gerar hashes
 * diferentes em execuções distintas — não observado nas fontes testadas até agora, mas
 * fica registrado caso apareça um falso "SEM_MUDANCA"/"mudou" inesperado no futuro.
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
        List<JsonNode> todasFeatures = buscarTodasAsPaginas(fonte);

        // Hash só das features, não da resposta inteira — vários provedores mandam
        // timestamp/metadado que muda a cada chamada mesmo com o dado igual
        // (CLAUDE.md, RN018).
        ArrayNode featuresParaHash = objectMapper.createArrayNode();
        todasFeatures.forEach(featuresParaHash::add);
        String conteudoParaHash = escreverJson(featuresParaHash);

        List<RegistroPreparado> registros = new ArrayList<>();
        for (JsonNode feature : todasFeatures) {
            registros.add(new RegistroPreparado(
                    escreverJson(feature.path("properties")),
                    wktMapper.paraWkt(feature.path("geometry"))));
        }

        return writer.gravar(fonte, conteudoParaHash, registros);
    }

    private List<JsonNode> buscarTodasAsPaginas(FonteDado fonte) {
        PaginadorUrlGeoJson.Familia familia = PaginadorUrlGeoJson.detectarFamilia(fonte.getUrlOrigem());
        List<JsonNode> todasFeatures = new ArrayList<>();
        int offset = 0;

        for (int pagina = 1; ; pagina++) {
            String urlPagina = PaginadorUrlGeoJson.montarUrlPagina(fonte.getUrlOrigem(), familia, offset);
            String corpoBruto = httpFetchClient.buscarCorpoBruto(urlPagina);
            JsonNode featureCollection = lerJson(corpoBruto, fonte);
            JsonNode features = featureCollection.path("features");
            features.forEach(todasFeatures::add);

            boolean ultimaPagina = familia == PaginadorUrlGeoJson.Familia.DESCONHECIDA
                    || features.size() < PaginadorUrlGeoJson.TAMANHO_PAGINA;
            if (ultimaPagina) {
                return todasFeatures;
            }
            if (pagina >= PaginadorUrlGeoJson.MAX_PAGINAS) {
                throw new IllegalStateException(
                        "Fonte \"" + fonte.getNome() + "\" passou de " + PaginadorUrlGeoJson.MAX_PAGINAS
                                + " páginas (" + todasFeatures.size()
                                + " registros) - parando por segurança, algo pode estar errado.");
            }
            offset += features.size();
        }
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
