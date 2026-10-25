package br.gov.sp.cps.datagis.controle;

import br.gov.sp.cps.datagis.config.OperacaoEmAndamentoException;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoRegistry;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoResponse;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lotes")
@Transactional(readOnly = true)
public class LoteIngestaoController {

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final TransicaoBronzeSilverService transicaoBronzeSilverService;
    private final ProgressoTransicaoRegistry progressoTransicaoRegistry;

    @Autowired
    public LoteIngestaoController(
            LoteIngestaoRepository loteIngestaoRepository,
            TransicaoBronzeSilverService transicaoBronzeSilverService,
            @Autowired(required = false) ProgressoTransicaoRegistry progressoTransicaoRegistry) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.transicaoBronzeSilverService = transicaoBronzeSilverService;
        this.progressoTransicaoRegistry = progressoTransicaoRegistry;
    }

    public LoteIngestaoController(
            LoteIngestaoRepository loteIngestaoRepository,
            TransicaoBronzeSilverService transicaoBronzeSilverService) {
        this(loteIngestaoRepository, transicaoBronzeSilverService, null);
    }

    @GetMapping
    public ResponseEntity<List<LoteDto>> listarTodos() {
        List<LoteDto> lotes = loteIngestaoRepository.findAllComFonteOrderByIdDesc()
                .stream()
                .map(LoteDto::fromEntity)
                .toList();
        return ResponseEntity.ok(lotes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoteDto> buscarPorId(@PathVariable Long id) {
        return loteIngestaoRepository.findByIdComFonte(id)
                .map(LoteDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retorna o progresso atual da transição/revalidação do lote.
     */
    @GetMapping("/{id}/progresso")
    public ResponseEntity<ProgressoTransicaoResponse> progresso(@PathVariable Long id) {
        ProgressoTransicaoResponse resp = progressoTransicaoRegistry != null
                ? progressoTransicaoRegistry.obter(id).map(ProgressoTransicaoResponse::de).orElseGet(() -> ProgressoTransicaoResponse.ocioso(id))
                : ProgressoTransicaoResponse.ocioso(id);
        return ResponseEntity.ok(resp);
    }

    /**
     * Roda a validação Bronze→Silver de novo pros registros já ingeridos desse lote
     * de forma assíncrona desacoplada da thread HTTP.
     */
    @PostMapping("/{id}/reprocessar")
    @Transactional
    public ResponseEntity<ProgressoTransicaoResponse> reprocessar(@PathVariable Long id) {
        if (progressoTransicaoRegistry != null && progressoTransicaoRegistry.isExecutando(id)) {
            throw new OperacaoEmAndamentoException("O lote #" + id + " já está sendo reprocessado.");
        }
        if (progressoTransicaoRegistry != null) {
            progressoTransicaoRegistry.iniciar(id, 0, "INICIANDO");
        }
        transicaoBronzeSilverService.processarLoteAssincrono(id);
        ProgressoTransicaoResponse resp = progressoTransicaoRegistry != null
                ? progressoTransicaoRegistry.obter(id).map(ProgressoTransicaoResponse::de).orElseGet(() -> ProgressoTransicaoResponse.ocioso(id))
                : ProgressoTransicaoResponse.ocioso(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }
}
