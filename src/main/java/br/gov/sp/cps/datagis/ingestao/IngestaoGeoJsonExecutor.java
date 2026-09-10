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
 * <p><b>Redução automática de página:</b> se uma página falhar (achado ao vivo: a Malha
 * Municipal do IBGE/IAT-PR quebra com HTTP 500 ao converter ~500+ polígonos municipais
 * pra GeoJSON de uma vez, mesmo servidor que aguenta 1000+ pontos numa página sem
 * problema), tenta de novo com metade do tamanho, até {@link PaginadorUrlGeoJson#TAMANHO_MINIMO_PAGINA}.
 * Isso resolve o problema sem precisar de um tamanho de página fixo por fonte — cada
 * fonte "encontra" o próprio limite na hora.
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
                    paraWktOuNulo(feature)));
        }

        return writer.gravar(fonte, conteudoParaHash, registros);
    }

    /**
     * Achado ao vivo no "IAT/PR - Embargos ICMBio": um registro real da fonte tem
     * geometria malformada (um polígono dentro do MultiPolygon vem {@code null} no
     * array de coordenadas), o que derrubava o lote inteiro com NullPointerException —
     * um único registro ruim não pode impedir os outros milhares de serem gravados.
     * Mesmo princípio já usado pra geometria ausente (RN007: não inventamos dado, só
     * preservamos o que veio - aqui preservamos os atributos e deixamos a geometria
     * vazia em vez de rejeitar o registro inteiro).
     */
    private String paraWktOuNulo(JsonNode feature) {
        try {
            return wktMapper.paraWkt(feature.path("geometry"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Página já buscada, junto do tamanho de página que efetivamente funcionou (pode ter sido reduzido). */
    private record PaginaBuscada(List<JsonNode> features, int tamanhoUsado) {
    }

    private List<JsonNode> buscarTodasAsPaginas(FonteDado fonte) {
        PaginadorUrlGeoJson.Familia familia = PaginadorUrlGeoJson.detectarFamilia(fonte.getUrlOrigem());
        List<JsonNode> todasFeatures = new ArrayList<>();
        int offset = 0;
        int tamanhoPagina = PaginadorUrlGeoJson.TAMANHO_PAGINA;

        for (int pagina = 1; ; pagina++) {
            PaginaBuscada resultado = buscarPaginaComReducaoAutomatica(fonte, familia, offset, tamanhoPagina);
            todasFeatures.addAll(resultado.features());
            tamanhoPagina = resultado.tamanhoUsado();

            boolean ultimaPagina = familia == PaginadorUrlGeoJson.Familia.DESCONHECIDA
                    || resultado.features().size() < tamanhoPagina;
            if (ultimaPagina) {
                return todasFeatures;
            }
            if (pagina >= PaginadorUrlGeoJson.MAX_PAGINAS) {
                throw new IllegalStateException(
                        "Fonte \"" + fonte.getNome() + "\" passou de " + PaginadorUrlGeoJson.MAX_PAGINAS
                                + " páginas (" + todasFeatures.size()
                                + " registros) - parando por segurança, algo pode estar errado.");
            }
            offset += resultado.features().size();
        }
    }

    /**
     * Tentativas no MESMO tamanho de página antes de reduzir — cobre instabilidade
     * passageira do servidor (achado ao vivo: "IAT/PR - Embargos ICMBio" falhou duas
     * vezes com HTTP 500 e, minutos depois, a mesma URL exata funcionou de primeira —
     * não tinha nada a ver com tamanho de página, o servidor só estava instável
     * naquele instante).
     */
    private static final int TENTATIVAS_POR_TAMANHO = 4;
    private static final long ESPERA_ENTRE_TENTATIVAS_MS = 1500;

    /**
     * Busca uma página. Duas estratégias de recuperação, nessa ordem:
     * <ol>
     *   <li>Repete no MESMO tamanho de página (com uma pequena espera) — resolve
     *       instabilidade passageira do servidor;</li>
     *   <li>Se continuar falhando, reduz o tamanho pela metade e recomeça, até o piso
     *       {@link PaginadorUrlGeoJson#TAMANHO_MINIMO_PAGINA} — resolve o caso de o
     *       servidor não aguentar converter muita geometria pesada pra GeoJSON de uma
     *       vez (achado ao vivo na Malha Municipal do IBGE/IAT-PR).</li>
     * </ol>
     * O tamanho que funcionou volta no resultado — as páginas seguintes desta mesma
     * execução já nascem com ele.
     */
    private PaginaBuscada buscarPaginaComReducaoAutomatica(
            FonteDado fonte, PaginadorUrlGeoJson.Familia familia, int offset, int tamanhoPagina) {
        int tamanhoTentativa = tamanhoPagina;
        RuntimeException ultimaFalha = null;

        while (familia == PaginadorUrlGeoJson.Familia.DESCONHECIDA
                || tamanhoTentativa >= PaginadorUrlGeoJson.TAMANHO_MINIMO_PAGINA) {
            String urlPagina = PaginadorUrlGeoJson.montarUrlPagina(fonte.getUrlOrigem(), familia, offset, tamanhoTentativa);

            for (int tentativa = 1; tentativa <= TENTATIVAS_POR_TAMANHO; tentativa++) {
                try {
                    String corpoBruto = httpFetchClient.buscarCorpoBruto(urlPagina);
                    JsonNode featureCollection = lerJson(corpoBruto, fonte);
                    List<JsonNode> features = new ArrayList<>();
                    featureCollection.path("features").forEach(features::add);
                    return new PaginaBuscada(features, tamanhoTentativa);
                } catch (RuntimeException e) {
                    ultimaFalha = e;
                    if (tentativa < TENTATIVAS_POR_TAMANHO) {
                        aguardar();
                    }
                }
            }
            if (familia == PaginadorUrlGeoJson.Familia.DESCONHECIDA) {
                break;
            }
            tamanhoTentativa /= 2;
        }
        throw new IllegalStateException(
                "Fonte \"" + fonte.getNome() + "\" falhou ao buscar página em offset=" + offset
                        + " mesmo reduzindo o tamanho de página até " + PaginadorUrlGeoJson.TAMANHO_MINIMO_PAGINA,
                ultimaFalha);
    }

    private void aguardar() {
        try {
            Thread.sleep(ESPERA_ENTRE_TENTATIVAS_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
