package br.gov.sp.cps.datagis.controle;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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

        FonteDado fonte = fonteDadoRepository.findById(fonteId).orElse(null);
        if (fonte == null) {
            return ResponseEntity.notFound().build();
        }

        // 1. Validar duplicidade dentro da própria lista recebida
        java.util.Set<String> atributosVistos = new java.util.HashSet<>();
        for (MapeamentoColuna m : mapeamentos) {
            if (!"JSON".equalsIgnoreCase(m.getDestinoTipo()) && m.getAtributoDestino() != null) {
                String attrNorm = m.getAtributoDestino().trim().toLowerCase();
                if (!attrNorm.isBlank() && !atributosVistos.add(attrNorm)) {
                    throw new IllegalArgumentException(
                            "A coluna Silver \"" + m.getAtributoDestino() + "\" foi informada mais de uma vez na mesma requisição.");
                }
            }
        }

        // 2. Validar duplicidade contra mapeamentos já cadastrados no banco para esta fonte
        List<MapeamentoColuna> existentes = repository.findByFonteDadoId(fonteId);
        for (MapeamentoColuna m : mapeamentos) {
            if (!"JSON".equalsIgnoreCase(m.getDestinoTipo()) && m.getAtributoDestino() != null) {
                String attrNorm = m.getAtributoDestino().trim().toLowerCase();
                boolean duplicado = existentes.stream().anyMatch(ex ->
                        (m.getId() == null || !ex.getId().equals(m.getId()))
                                && !"JSON".equalsIgnoreCase(ex.getDestinoTipo())
                                && ex.getAtributoDestino() != null
                                && ex.getAtributoDestino().trim().equalsIgnoreCase(attrNorm));

                if (duplicado) {
                    throw new IllegalArgumentException(
                            "A coluna Silver \"" + m.getAtributoDestino() + "\" já está mapeada nesta fonte e não pode ser preenchida novamente.");
                }
            }
        }

        for (MapeamentoColuna m : mapeamentos) {
            m.setFonteDadoId(fonteId);
            m.setValidarRecortePr(false);
            if (m.getTabelaDestino() == null || m.getTabelaDestino().isBlank()) {
                m.setTabelaDestino(fonte.getEntidadeSilver());
            }
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

    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> removerTodosPorFonte(@PathVariable Long fonteId) {
        if (!fonteDadoRepository.existsById(fonteId)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteByFonteDadoId(fonteId);
        return ResponseEntity.noContent().build();
    }
}
