package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.armazenamento.ArmazenamentoArquivoBruto;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Ponto único de disparo de ingestão para qualquer fonte cadastrada. Não sabe nada
 * sobre CAR, IBGE, embargos ou qualquer fonte específica — só carrega a
 * {@link FonteDado} pelo id e delega pro primeiro {@link IngestaoExecutor} registrado
 * que responder que sabe processá-la. Spring injeta automaticamente todo bean que
 * implemente a interface na lista {@code executores}, então uma fonte nova só precisa
 * de um executor novo — nada aqui muda.
 */
@Service
public class IngestaoExecucaoService {

    private final FonteDadoRepository fonteDadoRepository;
    private final List<IngestaoExecutor> executores;
    private final ArmazenamentoArquivoBruto armazenamento;
    private final ProgressoExecucaoRegistry progresso;

    public IngestaoExecucaoService(
            FonteDadoRepository fonteDadoRepository,
            List<IngestaoExecutor> executores,
            ArmazenamentoArquivoBruto armazenamento,
            ProgressoExecucaoRegistry progresso) {
        this.fonteDadoRepository = fonteDadoRepository;
        this.executores = executores;
        this.armazenamento = armazenamento;
        this.progresso = progresso;
    }

    /** Maior mensagem de erro que vai pra tela — evita estourar o VARCHAR2(2000) da coluna. */
    private static final int TAMANHO_MAXIMO_MENSAGEM_ERRO = 2000;

    public LoteIngestao executar(Long fonteId) {
        FonteDado fonte = buscarFonte(fonteId);

        IngestaoExecutor executor = executores.stream()
                .filter(e -> e.suportar(fonte))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ainda não existe ingestão implementada para a fonte \"" + fonte.getNome() + "\""));

        progresso.iniciar(fonte.getId());
        long inicio = System.currentTimeMillis();
        try {
            LoteIngestao lote = executor.executar(fonte);
            registrarExecucao(fonte, inicio, "SUCESSO", null);
            progresso.finalizar(fonte.getId(), FaseExecucao.CONCLUIDO);
            return lote;
        } catch (Throwable falha) {
            // Throwable, não só Exception, de propósito: um OutOfMemoryError (Error,
            // não Exception) já aconteceu ao vivo nesta fonte (IngestaoGeoJsonExecutor
            // processando uma resposta grande demais) e, sem isso aqui, só aparecia no
            // log do container - a tela nunca soube que a execução tinha falhado nem
            // por quê. Sempre relança em seguida; isto só registra o que aconteceu.
            registrarExecucao(fonte, inicio, "ERRO", mensagemDeErro(falha));
            progresso.finalizar(fonte.getId(), FaseExecucao.ERRO);
            throw falha;
        }
    }

    private void registrarExecucao(FonteDado fonte, long inicioMs, String status, String mensagemErro) {
        fonte.setUltimaExecucaoEm(LocalDateTime.now());
        fonte.setUltimaDuracaoMs(System.currentTimeMillis() - inicioMs);
        fonte.setUltimoStatusExecucao(status);
        fonte.setUltimoErroMensagem(mensagemErro);
        fonteDadoRepository.save(fonte);
    }

    private String mensagemDeErro(Throwable falha) {
        String mensagem = falha.getClass().getSimpleName()
                + (falha.getMessage() != null ? ": " + falha.getMessage() : "");
        return mensagem.length() > TAMANHO_MAXIMO_MENSAGEM_ERRO
                ? mensagem.substring(0, TAMANHO_MAXIMO_MENSAGEM_ERRO)
                : mensagem;
    }

    /**
     * Salva o arquivo enviado (nome original + conteúdo) como o "último arquivo" da
     * fonte e, na sequência, dispara a mesma ingestão genérica de {@link #executar} —
     * só fontes {@code UPLOAD_MANUAL} chegam até aqui de verdade (quem tem URL usa o
     * fluxo normal); a validação de tipo mora no executor, não aqui.
     */
    public LoteIngestao executarComUpload(Long fonteId, String nomeArquivo, InputStream conteudo) {
        FonteDado fonte = buscarFonte(fonteId);
        String endereco = armazenamento.salvar(nomeArquivo, conteudo);
        fonte.setCaminhoArquivoUpload(endereco);
        fonteDadoRepository.save(fonte);
        return executar(fonteId);
    }

    private FonteDado buscarFonte(Long fonteId) {
        return fonteDadoRepository.findById(fonteId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fonte de dado " + fonteId + " não encontrada"));
    }
}
