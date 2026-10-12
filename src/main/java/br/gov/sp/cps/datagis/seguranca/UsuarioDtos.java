package br.gov.sp.cps.datagis.seguranca;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** DTOs da administracao de usuarios ({@code /api/admin/**}). */
public final class UsuarioDtos {

    static final String FORMATO_LOGIN = "^[A-Za-z0-9._@-]+$";
    static final String MENSAGEM_LOGIN =
            "use so letras sem acento, numeros, ponto, hifen, sublinhado ou @, sem espacos (ex.: maria.silva)";

    private UsuarioDtos() {
    }

    /**
     * Usuario como a tela de administracao mostra.
     *
     * @param perfis             perfis atribuidos diretamente
     * @param permissoesAvulsas  permissoes atribuidas fora de perfil
     * @param permissoesEfetivas tudo que o usuario pode fazer (perfis expandidos + avulsas)
     */
    public record UsuarioResponse(
            String id,
            String login,
            String nome,
            String sobrenome,
            String email,
            boolean ativo,
            Instant criadoEm,
            List<String> perfis,
            List<String> permissoesAvulsas,
            List<String> permissoesEfetivas) {
    }

    /** Cadastro: a senha e temporaria, o Keycloak obriga a troca no primeiro login. */
    public record UsuarioNovoRequest(
            // Mesmo conjunto que o Keycloak aceita em username: sem espaco nem acento
            @NotBlank @Pattern(regexp = FORMATO_LOGIN, message = MENSAGEM_LOGIN) String login,
            @NotBlank String nome,
            @NotBlank String sobrenome,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "a senha temporaria precisa ter pelo menos 8 caracteres") String senhaTemporaria,
            List<String> roles) {
    }

    public record UsuarioAtualizacaoRequest(
            @NotBlank String nome,
            @NotBlank String sobrenome,
            @NotBlank @Email String email,
            @NotNull Boolean ativo) {
    }

    /** Lista COMPLETA de perfis + permissoes avulsas que o usuario deve ter (substitui as atuais). */
    public record RolesRequest(@NotNull List<String> roles) {
    }

    public record SenhaRequest(
            @NotBlank @Size(min = 8, message = "a senha temporaria precisa ter pelo menos 8 caracteres") String senhaTemporaria) {
    }

    public record PermissaoInfo(String codigo, String descricao) {
    }

    public record PerfilInfo(String codigo, String nome, String descricao, List<String> permissoes) {
    }

    /** Catalogo que a tela usa pra montar os checkboxes de perfis e permissoes. */
    public record CatalogoAcessoResponse(List<PerfilInfo> perfis, List<PermissaoInfo> permissoes) {
    }
}
