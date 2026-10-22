package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.CatalogoAcessoResponse;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.RolesRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.SenhaRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioAtualizacaoRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioNovoRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administracao de usuarios e controle de acesso (LIZARDS-47). Tudo sob
 * {@code /api/admin/**}, liberado so pra quem tem {@code usuario:gerenciar}
 * ({@code SecurityConfig}). O id do usuario e o id do Keycloak (UUID).
 */
@RestController
@RequestMapping("/api/admin")
public class UsuarioAdminController {

    private final UsuarioAdminService service;

    public UsuarioAdminController(UsuarioAdminService service) {
        this.service = service;
    }

    /** Perfis (com as permissoes de cada um) e permissoes existentes no sistema. */
    @GetMapping("/catalogo-acesso")
    public CatalogoAcessoResponse catalogo() {
        return service.catalogo();
    }

    @GetMapping("/usuarios")
    public List<UsuarioResponse> listar(@RequestParam(required = false) String busca) {
        return service.listar(busca);
    }

    @GetMapping("/usuarios/{id}")
    public UsuarioResponse buscar(@PathVariable String id) {
        return service.buscar(id);
    }

    @PostMapping("/usuarios")
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponse criar(@Valid @RequestBody UsuarioNovoRequest dados) {
        return service.criar(dados);
    }

    @PutMapping("/usuarios/{id}")
    public UsuarioResponse atualizar(@PathVariable String id, @Valid @RequestBody UsuarioAtualizacaoRequest dados,
                                     @AuthenticationPrincipal Jwt solicitante) {
        return service.atualizar(id, dados, solicitante.getSubject());
    }

    @PutMapping("/usuarios/{id}/roles")
    public UsuarioResponse definirRoles(@PathVariable String id, @Valid @RequestBody RolesRequest dados,
                                        @AuthenticationPrincipal Jwt solicitante) {
        return service.definirRoles(id, dados.roles(), solicitante.getSubject());
    }

    @PutMapping("/usuarios/{id}/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(@PathVariable String id, @Valid @RequestBody SenhaRequest dados) {
        service.redefinirSenha(id, dados.senhaTemporaria());
    }
}
