package br.gov.sp.cps.datagis.gold;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ImovelPublicadoService {

    private final ImovelPublicadoRepository repository;

    public ImovelPublicadoService(ImovelPublicadoRepository repository) {
        this.repository = repository;
    }

    public List<ImovelPublicado> listarTodos() {
        return repository.findAll();
    }

    public ImovelPublicado buscarPorCodImovel(String codImovel) {
        return repository.findByCodImovel(codImovel)
                .orElseThrow(() -> new RuntimeException("Imóvel não encontrado com o código: " + codImovel));
    }
}