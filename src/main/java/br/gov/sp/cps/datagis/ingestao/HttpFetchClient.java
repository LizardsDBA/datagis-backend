package br.gov.sp.cps.datagis.ingestao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * Baixa o corpo da URL direto pra um arquivo temporário, sem passar por
     * {@code String} — {@link #buscarCorpoBruto} decodifica como UTF-8, o que
     * corromperia um binário (ex.: o {@code .zip} de um Shapefile). Mesmo curl,
     * mesmo User-Agent (evita o mesmo bloqueio de WAF), só trocando a captura de
     * stdout por {@code -o <arquivo>}. Timeout maior (arquivo binário, não texto
     * de API) — o zip de biomas do IBGE já tem 14 MB e fontes maiores existem.
     *
     * @return caminho do arquivo temporário baixado; quem chama é responsável por apagá-lo.
     */
    public Path baixarParaArquivoTemporario(String url, String prefixoArquivo) {
        Path destino;
        try {
            destino = Files.createTempFile(prefixoArquivo, ".bin");
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao criar arquivo temporário para baixar " + url, e);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(
                "curl", "-sS", "-f", "-L", "--max-time", "300", "-A", USER_AGENT, "-o", destino.toString(), url);
        try {
            Process processo = processBuilder.start();
            String erro = new String(processo.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int codigoSaida = processo.waitFor();
            if (codigoSaida != 0) {
                apagarSilenciosamente(destino);
                throw new IllegalStateException(
                        "curl falhou (exit " + codigoSaida + ") ao baixar " + url + ": " + erro);
            }
            return destino;
        } catch (IOException e) {
            apagarSilenciosamente(destino);
            throw new IllegalStateException("Falha ao executar curl para baixar " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            apagarSilenciosamente(destino);
            throw new IllegalStateException("Download de " + url + " interrompido", e);
        }
    }

    private void apagarSilenciosamente(Path arquivo) {
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException ignorado) {
            // melhor esforco - arquivo temporario, nao trava a ingestao
        }
    }
}
