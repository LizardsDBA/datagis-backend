package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcRole;
import java.util.Collection;
import org.springframework.stereotype.Service;

/**
 * Regras do token de acesso (buscar dados da Gold pela API; o token em si e entrega futura):
 * so usuario com o perfil Administrador pode ter o token liberado. Nao ha regra de senha
 * propria: basta o login de um Administrador (decisao da equipe, 2026-11-03).
 * A liberacao gravada ({@code controle.token_acesso_usuario}) so vale enquanto o usuario for
 * Administrador: tirar o perfil desliga o token ({@link #desligarSeNaoAdministrador}).
 */
@Service
public class TokenAcessoService {

    static final String PERFIL_EXIGIDO = Perfil.ADMINISTRADOR.codigo();

    static final String MENSAGEM_SO_ADMINISTRADOR =
            "So usuario com o perfil Administrador pode ter token de acesso";

    private final TokenAcessoRepositorio tokens;
    private final KeycloakAdminClient keycloak;

    public TokenAcessoService(TokenAcessoRepositorio tokens, KeycloakAdminClient keycloak) {
        this.tokens = tokens;
        this.keycloak = keycloak;
    }

    /** Token liberado e o usuario ainda e Administrador ({@code perfis} = roles diretas dele). */
    public boolean ativo(String usuarioId, Collection<String> perfis) {
        return perfis.contains(PERFIL_EXIGIDO) && tokens.habilitado(usuarioId);
    }

    public boolean ativo(String usuarioId) {
        return ehAdministrador(usuarioId) && tokens.habilitado(usuarioId);
    }

    /** Liga ou desliga. Ligar exige o perfil Administrador; desligar vale para qualquer um. */
    public void definir(String usuarioId, boolean habilitado, String alteradoPor) {
        if (habilitado && !ehAdministrador(usuarioId)) {
            throw new IllegalArgumentException(MENSAGEM_SO_ADMINISTRADOR);
        }
        tokens.definir(usuarioId, habilitado, alteradoPor);
    }

    /** Cadastro de usuario ja com token: confere o perfil antes de criar no Keycloak. */
    public void validarCadastroComToken(Collection<String> roles) {
        if (roles == null || !roles.contains(PERFIL_EXIGIDO)) {
            throw new IllegalArgumentException(MENSAGEM_SO_ADMINISTRADOR);
        }
    }

    /** Depois de trocar os perfis: sem o Administrador, a liberacao do token cai. */
    public void desligarSeNaoAdministrador(String usuarioId, String alteradoPor) {
        if (!ehAdministrador(usuarioId) && tokens.habilitado(usuarioId)) {
            tokens.definir(usuarioId, false, alteradoPor);
        }
    }

    private boolean ehAdministrador(String usuarioId) {
        return keycloak.rolesDiretas(usuarioId).stream().map(KcRole::name).anyMatch(PERFIL_EXIGIDO::equals);
    }
}
