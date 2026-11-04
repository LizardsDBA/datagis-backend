package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.seguranca.MinhaContaService.DadosRequest;
import br.gov.sp.cps.datagis.seguranca.MinhaContaService.MinhaContaResponse;
import br.gov.sp.cps.datagis.seguranca.MinhaContaService.TrocaSenhaRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conta do proprio usuario (tela "Minha conta"). So exige login ({@code SecurityConfig}):
 * o usuario vem do token, entao cada um ve e altera so a propria conta.
 */
@RestController
@RequestMapping("/api/minha-conta")
public class MinhaContaController {

    private final MinhaContaService service;

    public MinhaContaController(MinhaContaService service) {
        this.service = service;
    }

    @GetMapping
    public MinhaContaResponse buscar(@AuthenticationPrincipal Jwt usuario) {
        return service.buscar(usuario.getSubject());
    }

    @PutMapping
    public MinhaContaResponse atualizarDados(@AuthenticationPrincipal Jwt usuario, @Valid @RequestBody DadosRequest dados) {
        return service.atualizarDados(usuario.getSubject(), dados);
    }

    @PutMapping("/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trocarSenha(@AuthenticationPrincipal Jwt usuario, @Valid @RequestBody TrocaSenhaRequest dados) {
        service.trocarSenha(usuario.getSubject(), dados);
    }
}
