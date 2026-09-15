package br.gov.sp.cps.datagis.controle;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fontes/{fonteId}/mapeamentos")
public class MapeamentoColunaController {

    private final MapeamentoColunaRepository repository;
    private final FonteDadoRepository fonteDadoRepository;

    public MapeamentoColunaController(MapeamentoColunaRepository repository, FonteDadoRepository fonteDadoRepository) {
        this.repository = repository;
        this.fonteDadoRepository = fonteDadoRepository;
    }

    @GetMapping
    public ResponseEntity<List<MapeamentoColuna>> listarPorFonte(@PathVariable Long fonteId) {
        if (!fonteDadoRepository.existsById(fonteId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.findByFonteDadoId(fonteId));
    }

    @PostMapping
    public ResponseEntity<List<MapeamentoColuna>> salvarMapeamentos(
            @PathVariable Long fonteId,
            @RequestBody List<MapeamentoColuna> mapeamentos) {

        if (!fonteDadoRepository.existsById(fonteId)) {
            return ResponseEntity.notFound().build();
        }

        for (MapeamentoColuna m : mapeamentos) {
            m.setFonteDadoId(fonteId);
            if (m.getDataAdd() == null) {
                m.setDataAdd(java.time.LocalDateTime.now());
            }
        }
        return ResponseEntity.ok(repository.saveAll(mapeamentos));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remover(@PathVariable Long fonteId, @PathVariable Long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
