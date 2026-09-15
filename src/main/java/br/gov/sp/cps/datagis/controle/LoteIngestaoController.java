package br.gov.sp.cps.datagis.controle;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lotes")
@Transactional(readOnly = true)
public class LoteIngestaoController {

    private final LoteIngestaoRepository loteIngestaoRepository;

    public LoteIngestaoController(LoteIngestaoRepository loteIngestaoRepository) {
        this.loteIngestaoRepository = loteIngestaoRepository;
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
}
