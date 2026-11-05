package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.auditoria.MudancaAuditada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liberacao da "Criacao de token de acesso" de um usuario, feita pelo administrador (cai na
 * regra de {@code /api/admin/**}, {@code usuario:gerenciar}). So usuario com o perfil
 * Administrador pode ter token (TokenAcessoService). O token ainda nao existe: por enquanto
 * so se guarda a liberacao, que a pessoa ve na tela "Minha conta".
 */
@RestController
@RequestMapping("/api/admin/usuarios/{id}/token-acesso")
public class TokenAcessoAdminController {

    private final TokenAcessoService tokens;
    private final KeycloakAdminClient keycloak;

    public TokenAcessoAdminController(TokenAcessoService tokens, KeycloakAdminClient keycloak) {
        this.tokens = tokens;
        this.keycloak = keycloak;
    }

    @GetMapping
    public TokenAcessoResponse buscar(@PathVariable String id) {
        return new TokenAcessoResponse(tokens.ativo(id));
    }

    @PutMapping
    public TokenAcessoResponse definir(@PathVariable String id, @Valid @RequestBody TokenAcessoRequest dados,
                                       @AuthenticationPrincipal Jwt solicitante) {
        // Confere que o usuario existe (404 do Keycloak vira 400 "Registro nao encontrado")
        String login = keycloak.buscarUsuario(id).username();
        boolean antes = tokens.ativo(id);
        tokens.definir(id, dados.habilitado(), solicitante.getClaimAsString("preferred_username"));
        MudancaAuditada.registrar(foto(login, antes), foto(login, dados.habilitado()));
        return new TokenAcessoResponse(dados.habilitado());
    }

    // Ordem fixa das chaves, pra janela de antes e depois do log ficar sempre igual
    private static Map<String, Object> foto(String login, boolean habilitado) {
        Map<String, Object> foto = new LinkedHashMap<>();
        foto.put("login", login);
        foto.put("tokenAcessoHabilitado", habilitado);
        return foto;
    }

    public record TokenAcessoRequest(@NotNull Boolean habilitado) {
    }

    public record TokenAcessoResponse(boolean habilitado) {
    }
}
