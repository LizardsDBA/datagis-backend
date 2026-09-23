package br.gov.sp.cps.datagis.gold;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST para consulta, cadastro, atualização e desativação de tipos de indicadores (camada Gold).
 */
@RestController
@RequestMapping("/api/indicadores/tipos")
public class TipoIndicadorController {

    private final TipoIndicadorService service;

    public TipoIndicadorController(TipoIndicadorService service) {
        this.service = service;
    }

    @GetMapping
    public List<TipoIndicadorResponse> listar() {
        return service.listar().stream()
                .map(TipoIndicadorResponse::de)
                .toList();
    }

    @GetMapping("/{id}")
    public TipoIndicadorResponse buscarPorId(@PathVariable Long id) {
        return TipoIndicadorResponse.de(service.buscarPorId(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TipoIndicadorResponse cadastrar(@Valid @RequestBody TipoIndicadorRequest dados) {
        return TipoIndicadorResponse.de(service.cadastrar(dados));
    }

    @PutMapping("/{id}")
    public TipoIndicadorResponse atualizar(@PathVariable Long id, @Valid @RequestBody TipoIndicadorRequest dados) {
        return TipoIndicadorResponse.de(service.atualizar(id, dados));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desativar(@PathVariable Long id) {
        service.desativar(id);
    }
}
