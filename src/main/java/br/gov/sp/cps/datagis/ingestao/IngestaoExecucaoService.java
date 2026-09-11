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

    public IngestaoExecucaoService(
            FonteDadoRepository fonteDadoRepository,
            List<IngestaoExecutor> executores,
            ArmazenamentoArquivoBruto armazenamento) {
        this.fonteDadoRepository = fonteDadoRepository;
        this.executores = executores;
        this.armazenamento = armazenamento;
    }

    public LoteIngestao executar(Long fonteId) {
        FonteDado fonte = buscarFonte(fonteId);

        IngestaoExecutor executor = executores.stream()
                .filter(e -> e.suportar(fonte))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ainda não existe ingestão implementada para a fonte \"" + fonte.getNome() + "\""));

        LoteIngestao lote = executor.executar(fonte);

        fonte.setUltimaExecucaoEm(LocalDateTime.now());
        fonteDadoRepository.save(fonte);

        return lote;
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
