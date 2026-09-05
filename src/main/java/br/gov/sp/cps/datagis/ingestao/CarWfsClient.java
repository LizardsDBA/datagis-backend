package br.gov.sp.cps.datagis.ingestao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Busca os dados do CAR no WFS do SICAR. Devolve o corpo como texto puro; quem
 * transforma em JSON e o service.
 *
 * Usamos curl em vez do HttpClient do Java porque o servidor do CAR bloqueia a
 * conexao TLS feita pelo Java (testamos bastante e sempre da erro de handshake),
 * mas com curl sempre funciona. Parece um bloqueio de firewall meio bobo que so
 * deixa passar clientes "conhecidos". Se um dia trocar a imagem do container,
 * confirmar que o curl continua instalado nela.
 *
 * Ainda sem paginacao: o tanto de municipio que ingerimos cabe numa pagina so.
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
