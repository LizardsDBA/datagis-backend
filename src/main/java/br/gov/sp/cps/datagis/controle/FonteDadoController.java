package br.gov.sp.cps.datagis.controle;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cadastro de fontes de dados: criar e listar. */
@RestController
@RequestMapping("/api/fontes")
public class FonteDadoController {

    private final FonteDadoService service;

    public FonteDadoController(FonteDadoService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FonteDadoResponse cadastrar(@Valid @RequestBody FonteDadoRequest dados) {
        return FonteDadoResponse.de(service.cadastrar(dados));
    }

    @GetMapping
    public List<FonteDadoResponse> listar() {
        return service.listar().stream().map(FonteDadoResponse::de).toList();
    }
}
