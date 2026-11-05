package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.auditoria.MudancaAuditada;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Tela "Minha conta": o proprio usuario ve os dados dele e troca so o nome e a senha. O
 * resto (login, e-mail, perfis, situacao, liberacao de token) e so leitura aqui — quem muda
 * e o administrador, na tela de Usuarios.
 *
 * <p>O id e sempre o "sub" do token (quem chama), nunca um parametro: ninguem altera a
 * conta de outra pessoa por aqui. A troca de senha confere a senha atual no Keycloak antes.
 */
@Service
public class MinhaContaService {

    private final KeycloakAdminClient keycloak;
    private final UsuarioAdminService usuarios;
    private final TokenAcessoService tokens;

    public MinhaContaService(KeycloakAdminClient keycloak, UsuarioAdminService usuarios, TokenAcessoService tokens) {
        this.keycloak = keycloak;
        this.usuarios = usuarios;
        this.tokens = tokens;
    }

    public MinhaContaResponse buscar(String id) {
        return resposta(usuarios.buscar(id), id);
    }

    public MinhaContaResponse atualizarDados(String id, DadosRequest dados) {
        UsuarioResponse antes = usuarios.buscar(id);
        // E-mail e situacao continuam os mesmos: so o administrador muda
        keycloak.atualizarUsuario(id, dados.nome().trim(), dados.sobrenome().trim(), antes.email(), antes.ativo());
        UsuarioResponse depois = usuarios.buscar(id);
        MudancaAuditada.registrar(nomeCompleto(antes), nomeCompleto(depois));
        return resposta(depois, id);
    }

    public void trocarSenha(String id, TrocaSenhaRequest dados) {
        if (dados.novaSenha().equals(dados.senhaAtual())) {
            throw new IllegalArgumentException("A nova senha precisa ser diferente da senha atual");
        }
        UsuarioResponse usuario = usuarios.buscar(id);
        if (!keycloak.senhaConfere(usuario.login(), dados.senhaAtual())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }
        // Nao temporaria: foi a propria pessoa que escolheu
        keycloak.definirSenha(id, dados.novaSenha(), false);
        // A senha nunca vai pro log: so o fato de ter sido trocada
        Map<String, Object> antes = new LinkedHashMap<>();
        antes.put("senha", "anterior");
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("senha", "nova senha definida pelo proprio usuario (o valor nao e gravado)");
        MudancaAuditada.registrar(antes, depois);
    }

    private MinhaContaResponse resposta(UsuarioResponse u, String id) {
        return new MinhaContaResponse(u.id(), u.login(), u.nome(), u.sobrenome(), u.email(), u.ativo(), u.criadoEm(),
                u.perfis(), tokens.ativo(id, u.perfis()));
    }

    private static Map<String, Object> nomeCompleto(UsuarioResponse u) {
        Map<String, Object> foto = new LinkedHashMap<>();
        foto.put("login", u.login());
        foto.put("nome", u.nome());
        foto.put("sobrenome", u.sobrenome());
        return foto;
    }

    /** O que a tela "Minha conta" mostra. */
    public record MinhaContaResponse(
            String id,
            String login,
            String nome,
            String sobrenome,
            String email,
            boolean ativo,
            Instant criadoEm,
            List<String> perfis,
            boolean tokenAcessoHabilitado) {
    }

    public record DadosRequest(@NotBlank String nome, @NotBlank String sobrenome) {
    }

    public record TrocaSenhaRequest(
            @NotBlank String senhaAtual,
            @NotBlank @Size(min = 8, message = "a nova senha precisa ter pelo menos 8 caracteres") String novaSenha) {
    }
}
