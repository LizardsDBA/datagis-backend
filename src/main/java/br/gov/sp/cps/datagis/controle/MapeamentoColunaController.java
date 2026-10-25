package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

        validarMapeamentos(fonteId, mapeamentos, null);

        for (MapeamentoColuna m : mapeamentos) {
            prepararMapeamento(m, fonteId, fonte.getEntidadeSilver());
        }
        return ResponseEntity.ok(repository.saveAll(mapeamentos));
    }

    @PutMapping("/atributo/{atributoDestino}")
    @Transactional
    public ResponseEntity<List<MapeamentoColuna>> sincronizarPorAtributo(
            @PathVariable Long fonteId,
            @PathVariable String atributoDestino,
            @RequestBody List<MapeamentoColuna> novosMapeamentos) {

        FonteDado fonte = fonteDadoRepository.findById(fonteId).orElse(null);
        if (fonte == null) {
            return ResponseEntity.notFound().build();
        }

        validarMapeamentos(fonteId, novosMapeamentos, atributoDestino);

        String atributoNorm = atributoDestino.trim();
        List<MapeamentoColuna> existentes = repository.findByFonteDadoIdAndAtributoDestinoIgnoreCase(fonteId, atributoNorm);
        Map<String, MapeamentoColuna> mapaExistentes = new HashMap<>();
        for (MapeamentoColuna ex : existentes) {
            if (ex.getColunaOrigem() != null) {
                mapaExistentes.put(ex.getColunaOrigem().trim().toLowerCase(Locale.ROOT), ex);
            }
        }

        List<MapeamentoColuna> paraSalvar = new ArrayList<>();
        Set<Long> idsMantidos = new HashSet<>();

        for (MapeamentoColuna m : novosMapeamentos) {
            String colOrigKey = m.getColunaOrigem() != null ? m.getColunaOrigem().trim().toLowerCase(Locale.ROOT) : "";
            MapeamentoColuna alvo = mapaExistentes.get(colOrigKey);
            if (alvo != null) {
                alvo.setColunaOrigem(m.getColunaOrigem().trim());
                alvo.setAtributoDestino(atributoNorm);
                alvo.setDestinoTipo(m.getDestinoTipo());
                alvo.setTipoDado(m.getTipoDado());
                alvo.setTabelaDestino(m.getTabelaDestino() != null && !m.getTabelaDestino().isBlank()
                        ? m.getTabelaDestino()
                        : fonte.getEntidadeSilver());
                alvo.setReferenciaEntidadeSilver(m.getReferenciaEntidadeSilver());
                alvo.setTabelaReferencia(m.getTabelaReferencia());
                alvo.setValorMinimo(m.getValorMinimo());
                alvo.setObrigatorio(m.getObrigatorio());
                alvo.setValidarGeometria(m.getValidarGeometria());
                alvo.setValidarCpfCnpj(m.getValidarCpfCnpj());
                alvo.setValidarRecortePr(false);
                alvo.setImpedirRegressao(m.getImpedirRegressao());
                alvo.setRegrasExtras(m.getRegrasExtras());
                paraSalvar.add(alvo);
                if (alvo.getId() != null) {
                    idsMantidos.add(alvo.getId());
                }
            } else {
                prepararMapeamento(m, fonteId, fonte.getEntidadeSilver());
                m.setId(null);
                m.setAtributoDestino(atributoNorm);
                paraSalvar.add(m);
            }
        }

        List<MapeamentoColuna> paraRemover = new ArrayList<>();
        for (MapeamentoColuna ex : existentes) {
            if (ex.getId() != null && !idsMantidos.contains(ex.getId())) {
                paraRemover.add(ex);
            }
        }

        if (!paraRemover.isEmpty()) {
            repository.deleteAll(paraRemover);
            repository.flush();
        }

        List<MapeamentoColuna> salvos = repository.saveAllAndFlush(paraSalvar);
        return ResponseEntity.ok(salvos);
    }

    @DeleteMapping("/atributo/{atributoDestino}")
    @Transactional
    public ResponseEntity<Void> removerPorAtributo(
            @PathVariable Long fonteId,
            @PathVariable String atributoDestino) {
        if (!fonteDadoRepository.existsById(fonteId)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteByFonteDadoIdAndAtributoDestinoIgnoreCase(fonteId, atributoDestino.trim());
        return ResponseEntity.noContent().build();
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

    private void prepararMapeamento(MapeamentoColuna m, Long fonteId, String entidadeSilverPadrao) {
        m.setFonteDadoId(fonteId);
        m.setValidarRecortePr(false);
        if (m.getTabelaDestino() == null || m.getTabelaDestino().isBlank()) {
            m.setTabelaDestino(entidadeSilverPadrao);
        }
        if (m.getDataAdd() == null) {
            m.setDataAdd(LocalDateTime.now());
        }
    }

    private void validarMapeamentos(Long fonteId, List<MapeamentoColuna> mapeamentos, String atributoSincronizado) {
        Set<String> paresVistos = new HashSet<>();
        Set<String> colunasOrigemVistas = new HashSet<>();

        for (MapeamentoColuna m : mapeamentos) {
            String colOrig = m.getColunaOrigem() != null ? m.getColunaOrigem().trim().toLowerCase(Locale.ROOT) : "";
            String attrDest = m.getAtributoDestino() != null ? m.getAtributoDestino().trim().toLowerCase(Locale.ROOT) : "";

            if (colOrig.isBlank() || attrDest.isBlank()) {
                throw new IllegalArgumentException("Coluna de origem e atributo de destino são obrigatórios.");
            }

            String par = colOrig + "->" + attrDest;
            if (!paresVistos.add(par)) {
                throw new IllegalArgumentException(
                        "O par de mapeamento (" + m.getColunaOrigem() + " -> " + m.getAtributoDestino() + ") foi informado mais de uma vez.");
            }

            if (!"JSON".equalsIgnoreCase(m.getDestinoTipo()) && !colunasOrigemVistas.add(colOrig)) {
                throw new IllegalArgumentException(
                        "A coluna de origem \"" + m.getColunaOrigem() + "\" foi mapeada mais de uma vez nesta requisição.");
            }
        }

        List<MapeamentoColuna> existentes = repository.findByFonteDadoId(fonteId);
        for (MapeamentoColuna m : mapeamentos) {
            String colOrig = m.getColunaOrigem().trim().toLowerCase(Locale.ROOT);
            String attrDest = m.getAtributoDestino().trim().toLowerCase(Locale.ROOT);

            for (MapeamentoColuna ex : existentes) {
                if (atributoSincronizado != null && ex.getAtributoDestino() != null
                        && ex.getAtributoDestino().trim().equalsIgnoreCase(atributoSincronizado.trim())) {
                    continue;
                }

                if (m.getId() != null && m.getId().equals(ex.getId())) {
                    continue;
                }

                String exColOrig = ex.getColunaOrigem() != null ? ex.getColunaOrigem().trim().toLowerCase(Locale.ROOT) : "";
                String exAttrDest = ex.getAtributoDestino() != null ? ex.getAtributoDestino().trim().toLowerCase(Locale.ROOT) : "";

                if (colOrig.equalsIgnoreCase(exColOrig) && attrDest.equalsIgnoreCase(exAttrDest)) {
                    throw new IllegalArgumentException(
                            "O mapeamento (" + m.getColunaOrigem() + " -> " + m.getAtributoDestino() + ") já existe para esta fonte.");
                }

                if (!"JSON".equalsIgnoreCase(m.getDestinoTipo())
                        && !"JSON".equalsIgnoreCase(ex.getDestinoTipo())
                        && colOrig.equalsIgnoreCase(exColOrig)
                        && !attrDest.equalsIgnoreCase(exAttrDest)) {
                    throw new IllegalArgumentException(
                            "A coluna de origem \"" + m.getColunaOrigem() + "\" já está mapeada para a coluna Silver \""
                                    + ex.getAtributoDestino() + "\".");
                }
            }
        }
    }
}
