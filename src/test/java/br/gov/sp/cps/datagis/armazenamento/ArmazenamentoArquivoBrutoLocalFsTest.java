package br.gov.sp.cps.datagis.armazenamento;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArmazenamentoArquivoBrutoLocalFsTest {

    @TempDir
    Path diretorioTemp;

    @Test
    void deveSalvarELerDeVoltaOMesmoConteudo() throws IOException {
        ArmazenamentoArquivoBrutoLocalFs armazenamento =
                new ArmazenamentoArquivoBrutoLocalFs(diretorioTemp.toString());
        byte[] conteudoOriginal = "conteudo de teste".getBytes(StandardCharsets.UTF_8);

        String endereco = armazenamento.salvar("meu arquivo.zip", new ByteArrayInputStream(conteudoOriginal));

        try (InputStream lido = armazenamento.ler(endereco)) {
            assertThat(lido.readAllBytes()).isEqualTo(conteudoOriginal);
        }
    }

    @Test
    void nomeComCaminhoNaoEscapaDoDiretorioConfigurado() {
        ArmazenamentoArquivoBrutoLocalFs armazenamento =
                new ArmazenamentoArquivoBrutoLocalFs(diretorioTemp.toString());

        String endereco = armazenamento.salvar("../../etc/passwd", new ByteArrayInputStream(new byte[0]));

        // "/" do nome original vira "_" - o resultado e sempre 1 arquivo dentro do
        // diretorio configurado, nunca uma travessia (nao sobra separador de caminho
        // pra "resolve" seguir). Os ".." literais que sobram no nome sao só texto, não
        // path traversal - por isso a asserção real é sobre o PARENT, não substring.
        assertThat(Path.of(endereco).getParent()).isEqualTo(diretorioTemp);
    }

    @Test
    void enderecoDiferenteParaUploadsComMesmoNome() {
        ArmazenamentoArquivoBrutoLocalFs armazenamento =
                new ArmazenamentoArquivoBrutoLocalFs(diretorioTemp.toString());

        String primeiro = armazenamento.salvar("dados.zip", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        String segundo = armazenamento.salvar("dados.zip", new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));

        assertThat(primeiro).isNotEqualTo(segundo);
    }
}
