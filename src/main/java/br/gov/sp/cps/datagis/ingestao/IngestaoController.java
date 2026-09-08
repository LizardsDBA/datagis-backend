package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.LoteIngestao;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispara a ingestão de qualquer fonte cadastrada, pelo id. Não sabe qual fonte é CAR,
 * IBGE, embargos etc. — isso é responsabilidade de cada {@link IngestaoExecutor} (ver
 * {@link IngestaoExecucaoService}).
 */
@RestController
@RequestMapping("/api/fontes")
public class IngestaoController {

    private final IngestaoExecucaoService execucaoService;

    public IngestaoController(IngestaoExecucaoService execucaoService) {
        this.execucaoService = execucaoService;
    }

    @PostMapping("/{id}/executar")
    public IngestaoResultado executar(@PathVariable Long id) {
        LoteIngestao lote = execucaoService.executar(id);
        return new IngestaoResultado(lote.getId(), lote.getQuantidadeRegistros(), lote.getStatus());
    }
}
