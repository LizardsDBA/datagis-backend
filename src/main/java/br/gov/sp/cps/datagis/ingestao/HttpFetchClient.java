package br.gov.sp.cps.datagis.ingestao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Busca o corpo bruto de qualquer URL de fonte de dado. Devolve como texto puro; quem
 * chama decide como interpretar (JSON, CSV, o que for).
 *
 * <p>Usamos curl em vez do HttpClient do Java porque pelo menos uma fonte real (o WFS
 * do CAR/SICAR) bloqueia a conexão TLS feita pelo Java — testamos bastante e sempre dá
 * erro de handshake, mas com curl sempre funciona (parece bloqueio de firewall que só
 * deixa passar clientes "conhecidos"). Como isso não custa nada nas fontes que não têm
 * esse problema, usamos curl como caminho único e simples pra todas — inclusive com um
 * User-Agent de navegador, que evita bloqueio de outras fontes com WAF parecido (ex.:
 * FUNAI, ver docs/fontes-de-dados.md). Se um dia trocar a imagem do container, confirmar
 * que o curl continua instalado nela.
 */
@Component
public class HttpFetchClient {

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; DataGis/1.0)";

    public String buscarCorpoBruto(String url) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "curl", "-sS", "-f", "-L", "--max-time", "60", "-A", USER_AGENT, url);
        try {
            Process processo = processBuilder.start();
            String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String erro = new String(processo.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int codigoSaida = processo.waitFor();
            if (codigoSaida != 0) {
                throw new IllegalStateException(
                        "curl falhou (exit " + codigoSaida + ") ao buscar " + url + ": " + erro);
            }
            return saida;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao executar curl para buscar " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Busca de " + url + " interrompida", e);
        }
    }
}
