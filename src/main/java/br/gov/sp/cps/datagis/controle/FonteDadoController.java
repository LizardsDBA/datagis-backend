package br.gov.sp.cps.datagis.controle;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cadastro de fontes de dados: criar, editar, buscar e listar. */
@RestController
@RequestMapping("/api/fontes")
public class FonteDadoController {

    private final FonteDadoService service;
    private final CatalogoSilverService catalogoSilverService;
    private final ValidacaoMapeamentoFonteService validacaoMapeamentoFonteService;

    public FonteDadoController(
            FonteDadoService service,
            CatalogoSilverService catalogoSilverService,
            ValidacaoMapeamentoFonteService validacaoMapeamentoFonteService) {
        this.service = service;
        this.catalogoSilverService = catalogoSilverService;
        this.validacaoMapeamentoFonteService = validacaoMapeamentoFonteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FonteDadoResponse cadastrar(@Valid @RequestBody FonteDadoRequest dados) {
        FonteDado fonte = service.cadastrar(dados);
        return FonteDadoResponse.de(fonte, validacaoMapeamentoFonteService.avaliarStatus(fonte));
    }

    @GetMapping
    public List<FonteDadoResponse> listar() {
        return service.listar().stream()
                .map(fonte -> FonteDadoResponse.de(fonte, validacaoMapeamentoFonteService.avaliarStatus(fonte)))
                .toList();
    }

    @GetMapping("/esquemas-silver")
    public List<CatalogoSilverService.EsquemaSilverDto> listarEsquemasSilver() {
        return catalogoSilverService.listarEsquemas();
    }

    @GetMapping("/{id}")
    public FonteDadoResponse buscar(@PathVariable Long id) {
        FonteDado fonte = service.buscarPorId(id);
        return FonteDadoResponse.de(fonte, validacaoMapeamentoFonteService.avaliarStatus(fonte));
    }

    @PutMapping("/{id}")
    public FonteDadoResponse atualizar(@PathVariable Long id, @Valid @RequestBody FonteDadoRequest dados) {
        FonteDado fonte = service.atualizar(id, dados);
        return FonteDadoResponse.de(fonte, validacaoMapeamentoFonteService.avaliarStatus(fonte));
    }

    @PatchMapping("/{id}/entidade-silver")
    public FonteDadoResponse atualizarEntidadeSilver(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload) {
        String entidadeSilver = payload != null ? payload.get("entidadeSilver") : null;
        FonteDado fonte = service.atualizarEntidadeSilver(id, entidadeSilver);
        return FonteDadoResponse.de(fonte, validacaoMapeamentoFonteService.avaliarStatus(fonte));
    }
}
