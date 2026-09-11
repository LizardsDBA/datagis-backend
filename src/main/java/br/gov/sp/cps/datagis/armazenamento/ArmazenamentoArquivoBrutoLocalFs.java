package br.gov.sp.cps.datagis.armazenamento;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Única implementação hoje (CLAUDE.md §5.4): disco local do container. O "endereço"
 * devolvido é o caminho absoluto do arquivo salvo.
 *
 * <p>Vive em {@code /tmp} por padrão — sobrevive a `docker compose restart`, mas não a
 * `down`/recriação do container. Suficiente pro fluxo de upload-e-processa-na-hora de
 * hoje (o arquivo só precisa existir entre o upload e a execução da ingestão, que
 * acontece na mesma chamada — ver {@code IngestaoExecucaoService.executarComUpload}).
 */
@Component
public class ArmazenamentoArquivoBrutoLocalFs implements ArmazenamentoArquivoBruto {

    private final Path diretorio;

    public ArmazenamentoArquivoBrutoLocalFs(
            @Value("${app.armazenamento.diretorio:/tmp/datagis-uploads}") String diretorio) {
        this.diretorio = Path.of(diretorio);
        try {
            Files.createDirectories(this.diretorio);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível criar o diretório de upload: " + this.diretorio, e);
        }
    }

    @Override
    public String salvar(String nomeOriginal, InputStream conteudo) {
        // UUID, nao so timestamp - dois uploads na mesma chamada podem cair no mesmo
        // milissegundo e colidir (achado escrevendo o teste desta classe).
        String nomeSeguro = UUID.randomUUID() + "-" + sanitizar(nomeOriginal);
        Path destino = diretorio.resolve(nomeSeguro);
        try {
            Files.copy(conteudo, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao salvar arquivo enviado: " + nomeOriginal, e);
        }
        return destino.toString();
    }

    @Override
    public InputStream ler(String endereco) {
        try {
            return Files.newInputStream(Path.of(endereco));
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler arquivo armazenado: " + endereco, e);
        }
    }

    /** Nome original vira parte do nome em disco só pra depuração — nunca confiar nele cru. */
    private static String sanitizar(String nomeOriginal) {
        String base = nomeOriginal == null ? "arquivo" : nomeOriginal;
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
