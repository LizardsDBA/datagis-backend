package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.LoteIngestao;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fatia 1 (CLAUDE.md §5.2) — só ingestão bruta, sem validação/quarentena/cálculo. */
@RestController
@RequestMapping("/api/ingestao")
public class IngestaoController {

    private final IngestaoCarService service;

    public IngestaoController(IngestaoCarService service) {
        this.service = service;
    }

    @PostMapping("/car/pr")
    public IngestaoResultado ingerirCarPr() {
        LoteIngestao lote = service.ingerir();
        return new IngestaoResultado(lote.getId(), lote.getQuantidadeRegistros(), lote.getStatus());
    }
}
