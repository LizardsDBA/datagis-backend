package br.gov.sp.cps.datagis.gold;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Proxy do GeoServer do mapa de cobertura (LIZARDS-35).
 *
 * <p>O navegador nunca fala com o GeoServer direto: chama
 * {@code /api/indicadores/geoserver/{wms|wfs}} com o token do Keycloak (a regra de
 * {@code indicador:visualizar} do SecurityConfig vale aqui), e o backend repassa so o
 * que esta na lista fechada abaixo - servico, operacao de leitura e camada
 * {@code vw_mapa_*}. Qualquer outra coisa volta 400 sem chegar no GeoServer.
 *
 * <p>Encaminha pro servico virtual do workspace ({@code /geoserver/datagis/wms}), que
 * por si so ja nao enxerga camada de outro workspace - a lista fechada e a segunda
 * barreira, nao a unica.
 */
@Service
@EnableConfigurationProperties(GeoServerProperties.class)
public class GeoServerProxyService {

    static final String WORKSPACE = "datagis";

    /** As views da V080. Camada nova no mapa entra aqui tambem. */
    static final Set<String> CAMADAS = Set.of(
            "vw_mapa_limite_pr", "vw_mapa_municipio", "vw_mapa_imovel", "vw_mapa_area_protegida",
            "vw_mapa_embargo", "vw_mapa_desmatamento", "vw_mapa_foco_calor");

    /** So leitura. Nada de Transaction (WFS-T), GetCapabilities ou GetFeatureInfo. */
    private static final Map<String, Set<String>> OPERACOES_POR_SERVICO = Map.of(
            "wms", Set.of("getmap", "getlegendgraphic"),
            "wfs", Set.of("getfeature", "describefeaturetype"));

    /** Parametros OGC que nomeiam camada (o nome dos parametros nao diferencia maiuscula). */
    private static final Set<String> PARAMETROS_DE_CAMADA = Set.of(
            "LAYERS", "LAYER", "QUERY_LAYERS", "TYPENAMES", "TYPENAME");

    /** SLD por URL faria o GeoServer buscar arquivo de fora; SLD_BODY troca o estilo inteiro. */
    private static final Set<String> PARAMETROS_PROIBIDOS = Set.of("SLD", "SLD_BODY");

    /** querySingle/queryCollection leem OUTRA camada de dentro do filtro - tambem precisam estar na lista. */
    private static final Pattern CAMADA_NO_FILTRO =
            Pattern.compile("query(?:Single|Collection)\\s*\\(\\s*'([^']*)'", Pattern.CASE_INSENSITIVE);

    private final RestClient http;
    private final String urlBase;

    @Autowired
    public GeoServerProxyService(GeoServerProperties props) {
        this(props, RestClient.builder().requestFactory(fabricaComTimeout(props)));
    }

    GeoServerProxyService(GeoServerProperties props, RestClient.Builder builder) {
        this.http = builder.build();
        this.urlBase = props.url().replaceAll("/+$", "");
    }

    public ResponseEntity<byte[]> encaminhar(String servico, MultiValueMap<String, String> parametros) {
        String servicoNormalizado = validar(servico, parametros);
        URI destino = URI.create(urlBase + "/" + WORKSPACE + "/" + servicoNormalizado + "?" + montarConsulta(parametros));
        try {
            ResponseEntity<byte[]> resposta = http.get().uri(destino)
                    .retrieve()
                    // Status do GeoServer passa adiante como veio, sem virar excecao aqui.
                    .onStatus(status -> true, (requisicao, respostaGeoServer) -> { })
                    .toEntity(byte[].class);
            HttpHeaders cabecalhos = new HttpHeaders();
            copiar(resposta.getHeaders(), cabecalhos, HttpHeaders.CONTENT_TYPE);
            copiar(resposta.getHeaders(), cabecalhos, HttpHeaders.CONTENT_DISPOSITION);
            return new ResponseEntity<>(resposta.getBody(), cabecalhos, resposta.getStatusCode());
        } catch (RestClientException e) {
            // IllegalStateException vira 502 no TratadorDeErro
            throw new IllegalStateException("GeoServer indisponivel: " + e.getMessage(), e);
        }
    }

    /** Devolve o servico em minusculas ou lanca IllegalArgumentException (400) dizendo o que foi recusado. */
    String validar(String servico, MultiValueMap<String, String> parametros) {
        String servicoNormalizado = servico == null ? "" : servico.toLowerCase(Locale.ROOT);
        Set<String> operacoes = OPERACOES_POR_SERVICO.get(servicoNormalizado);
        if (operacoes == null) {
            throw new IllegalArgumentException("Servico nao permitido: " + servico + " (use wms ou wfs)");
        }

        String parametroService = valorUnico(parametros, "SERVICE");
        if (parametroService != null && !parametroService.equalsIgnoreCase(servicoNormalizado)) {
            throw new IllegalArgumentException("SERVICE=" + parametroService + " nao confere com /" + servicoNormalizado);
        }

        String operacao = valorUnico(parametros, "REQUEST");
        if (operacao == null || !operacoes.contains(operacao.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Operacao nao permitida em " + servicoNormalizado + ": " + operacao);
        }

        for (Map.Entry<String, List<String>> parametro : parametros.entrySet()) {
            String nome = parametro.getKey().toUpperCase(Locale.ROOT);
            if (PARAMETROS_PROIBIDOS.contains(nome)) {
                throw new IllegalArgumentException("Parametro nao permitido: " + parametro.getKey());
            }
            for (String valor : parametro.getValue()) {
                if (PARAMETROS_DE_CAMADA.contains(nome)) {
                    for (String camada : valor.split(",")) {
                        exigirCamadaPermitida(camada);
                    }
                }
                if (nome.equals("CQL_FILTER")) {
                    Matcher camadaNoFiltro = CAMADA_NO_FILTRO.matcher(valor);
                    while (camadaNoFiltro.find()) {
                        exigirCamadaPermitida(camadaNoFiltro.group(1));
                    }
                }
            }
        }
        return servicoNormalizado;
    }

    private static void exigirCamadaPermitida(String camada) {
        String nome = camada.trim();
        if (nome.isEmpty()) {
            return;
        }
        String semWorkspace = nome.regionMatches(true, 0, WORKSPACE + ":", 0, WORKSPACE.length() + 1)
                ? nome.substring(WORKSPACE.length() + 1)
                : nome;
        if (!CAMADAS.contains(semWorkspace.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Camada nao permitida: " + nome);
        }
    }

    private static String valorUnico(MultiValueMap<String, String> parametros, String nome) {
        for (Map.Entry<String, List<String>> parametro : parametros.entrySet()) {
            if (parametro.getKey().equalsIgnoreCase(nome) && !parametro.getValue().isEmpty()) {
                return parametro.getValue().get(0);
            }
        }
        return null;
    }

    /** Codifica cada valor de novo: "+" em CQL ou data nao pode chegar no GeoServer como espaco. */
    private static String montarConsulta(MultiValueMap<String, String> parametros) {
        StringJoiner consulta = new StringJoiner("&");
        parametros.forEach((nome, valores) -> valores.forEach(valor -> consulta.add(
                URLEncoder.encode(nome, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(valor, StandardCharsets.UTF_8))));
        return consulta.toString();
    }

    private static void copiar(HttpHeaders origem, HttpHeaders destino, String cabecalho) {
        String valor = origem.getFirst(cabecalho);
        if (valor != null) {
            destino.set(cabecalho, valor);
        }
    }

    private static JdkClientHttpRequestFactory fabricaComTimeout(GeoServerProperties props) {
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.timeout()).build());
        fabrica.setReadTimeout(props.timeout());
        return fabrica;
    }
}
