package br.gov.sp.cps.datagis.gold;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/gold/imoveis")
public class ImovelPublicadoController {

    private final ImovelPublicadoService service;

    public ImovelPublicadoController(ImovelPublicadoService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ImovelPublicado>> listarTodos() {
        return ResponseEntity.ok(service.listarTodos());
    }

    @GetMapping("/{codImovel}")
    public ResponseEntity<ImovelPublicado> buscarPorCodImovel(@PathVariable String codImovel) {
        return ResponseEntity.ok(service.buscarPorCodImovel(codImovel));
    }
}