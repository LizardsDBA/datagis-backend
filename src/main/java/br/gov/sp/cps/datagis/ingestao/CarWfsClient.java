package br.gov.sp.cps.datagis.ingestao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Cliente do WFS do SICAR (CLAUDE.md §5.6). Devolve o corpo bruto como texto — a
 * conversão pra JSON acontece no service, pra manter aqui só a responsabilidade de
 * "buscar", e permitir hash fiel do que veio da rede (RN018/§2.8).
 *
 * <p><b>Por que via {@code curl} em vez de {@code RestClient}/JDK HttpClient:</b>
 * {@code geoserver.car.gov.br} está atrás de um WAF que bloqueia o handshake TLS do
 * {@code java.net.http.HttpClient} (fingerprint JA3/JA4 do JSSE), mesmo restringindo a
 * TLS 1.2 e mandando os mesmos cipher suites que o OpenSSL manda — confirmado com
 * {@code javax.net.debug=ssl:handshake}, sempre {@code handshake_failure} do lado do
 * Java, sempre HTTP 200 com {@code curl} (mesmo simulando o User-Agent do Java). Não é
 * rate-limit nem filtro de User-Agent — é bloqueio na camada TLS, antes até de qualquer
 * cabeçalho HTTP existir. Padrão de WAF anti-bot em infra pública brasileira que já
 * apareceu antes com o WFS do FUNAI (bloqueio por User-Agent, ver docs/fontes-de-dados.md).
 * Shell-out pro {@code curl} (presente na imagem de runtime) contorna isso porque usa
 * OpenSSL, não o JSSE do Java. Se um dia trocar de imagem base, confirmar que o `curl`
 * continua presente.
 *
 * Sem paginação nesta fatia: o circuito de municípios (§5.2) cabe numa página só.
 */
@Component
public class CarWfsClient {

    private final CarProperties props;

    public CarWfsClient(CarProperties props) {
        this.props = props;
    }

    public String buscarCorpoBruto() {
        String url = montarUrl();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "curl", "-sS", "-f", "--max-time", "60", url);
        try {
            Process processo = processBuilder.start();
            String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String erro = new String(processo.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int codigoSaida = processo.waitFor();
            if (codigoSaida != 0) {
                throw new IllegalStateException(
                        "curl falhou (exit " + codigoSaida + ") ao buscar CAR/PR: " + erro);
            }
            return saida;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao executar curl para buscar CAR/PR", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Busca do CAR/PR interrompida", e);
        }
    }

    private String montarUrl() {
        String cqlFilter = "cod_municipio_ibge IN (" + String.join(",", props.getMunicipiosIbge()) + ")";
        return UriComponentsBuilder.fromUriString(props.getWfsUrl())
                .queryParam("service", "WFS")
                .queryParam("version", "2.0.0")
                .queryParam("request", "GetFeature")
                .queryParam("typeNames", props.getLayer())
                .queryParam("outputFormat", "application/json")
                .queryParam("CQL_FILTER", cqlFilter)
                .encode()
                .build()
                .toUriString();
    }
}
