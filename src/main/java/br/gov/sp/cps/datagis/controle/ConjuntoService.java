package br.gov.sp.cps.datagis.controle;

import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConjuntoService {

    private final ConjuntoRepository conjuntoRepository;
    private final FonteDadoRepository fonteDadoRepository; // Assuming this exists based on the project structure

    @Transactional(readOnly = true)
    public List<ConjuntoResponse> listarTodos() {
        return conjuntoRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ConjuntoResponse criar(ConjuntoRequest request) {
        FonteDado fonte = fonteDadoRepository.findById(request.getFonteDadoId())
                .orElseThrow(() -> new IllegalArgumentException("Fonte de Dado não encontrada: " + request.getFonteDadoId()));

        Conjunto conjunto = Conjunto.builder()
                .fonteDado(fonte)
                .competencia(request.getCompetencia())
                .statusCamada(request.getStatusCamada())
                .coberturaTerritorial(request.getCoberturaTerritorial())
                .build();

        conjunto = conjuntoRepository.save(conjunto);
        return toResponse(conjunto);
    }

    private ConjuntoResponse toResponse(Conjunto conjunto) {
        return ConjuntoResponse.builder()
                .id(conjunto.getId())
                .fonteDadoId(conjunto.getFonteDado().getId())
                .fonteDadoNome(conjunto.getFonteDado().getNome())
                .competencia(conjunto.getCompetencia())
                .statusCamada(conjunto.getStatusCamada())
                .coberturaTerritorial(conjunto.getCoberturaTerritorial())
                .build();
    }
}
