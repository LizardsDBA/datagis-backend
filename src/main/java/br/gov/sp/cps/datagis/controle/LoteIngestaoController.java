package br.gov.sp.cps.datagis.controle;

import java.util.List;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
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

    public LoteIngestaoController(
            LoteIngestaoRepository loteIngestaoRepository,
            TransicaoBronzeSilverService transicaoBronzeSilverService) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.transicaoBronzeSilverService = transicaoBronzeSilverService;
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
     * Roda a validação Bronze→Silver de novo pros registros já ingeridos desse lote
     * (sem precisar buscar a fonte de novo) - útil depois de corrigir um bug na
     * esteira de validação (ex.: ValidadorCpfCnpj, LIZARDS-50) pra revalidar um lote
     * que tinha ido pra quarentena sem precisar que o dado na fonte externa mude
     * (a detecção de "sem mudança", RN018, bloquearia uma nova ingestão real).
     * Mesma regra All-or-Nothing de sempre.
     */
    @PostMapping("/{id}/reprocessar")
    @Transactional
    public ResponseEntity<TransicaoBronzeSilverService.ResultadoTransicao> reprocessar(@PathVariable Long id) {
        return ResponseEntity.ok(transicaoBronzeSilverService.processarLote(id));
    }
}
