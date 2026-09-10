package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
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

    public IngestaoExecucaoService(FonteDadoRepository fonteDadoRepository, List<IngestaoExecutor> executores) {
        this.fonteDadoRepository = fonteDadoRepository;
        this.executores = executores;
    }

    public LoteIngestao executar(Long fonteId) {
        FonteDado fonte = fonteDadoRepository.findById(fonteId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fonte de dado " + fonteId + " não encontrada"));

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
}
