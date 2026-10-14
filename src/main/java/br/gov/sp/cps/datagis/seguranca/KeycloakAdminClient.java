package br.gov.sp.cps.datagis.seguranca;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente fino da Admin REST API do Keycloak, autenticado como a conta de servico do
 * client {@code datagis-backend} (grant {@code client_credentials}). Cobre so o que o
 * sistema usa: roles do realm (catalogo de permissoes/perfis) e usuarios.
 *
 * <p>Escrito com {@link RestClient} em vez da lib {@code keycloak-admin-client}: aquela
 * traz RESTEasy + Jackson 2 e briga com o Boot 4/Jackson 3 do projeto (mesma classe de
 * pegadinha do CLAUDE.md §5.5 item 6). Sao meia duzia de endpoints REST simples.
 *
 * <p>Erros de comunicacao viram {@link IllegalStateException} (502 no {@code TratadorDeErro}),
 * conflito (login/e-mail repetido) e "nao encontrado" viram {@link IllegalArgumentException} (400).
 */
@Component
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<List<KcRole>> LISTA_ROLES = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<KcUsuario>> LISTA_USUARIOS = new ParameterizedTypeReference<>() { };

    private final KeycloakProperties props;
    private final RestClient http;

    private String tokenAtual;
    private Instant tokenExpiraEm = Instant.EPOCH;

    public KeycloakAdminClient(KeycloakProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(props.urlInterna()).build();
    }

    // ---------- Roles do realm ----------

    public List<KcRole> listarRoles() {
        return executar(() -> admin(HttpMethod.GET, "/roles?briefRepresentation=false").retrieve().body(LISTA_ROLES));
    }

    public void criarRole(String nome, String descricao) {
        executar(() -> admin(HttpMethod.POST, "/roles")
                .body(Map.of("name", nome, "description", descricao))
                .retrieve().toBodilessEntity());
    }

    public void atualizarDescricaoRole(String nome, String descricao) {
        executar(() -> admin(HttpMethod.PUT, "/roles/{nome}", nome)
                .body(Map.of("name", nome, "description", descricao))
                .retrieve().toBodilessEntity());
    }

    public List<KcRole> compostasDaRole(String nome) {
        return executar(() -> admin(HttpMethod.GET, "/roles/{nome}/composites", nome).retrieve().body(LISTA_ROLES));
    }

    public void adicionarCompostas(String nome, List<KcRole> roles) {
        if (roles.isEmpty()) {
            return;
        }
        executar(() -> admin(HttpMethod.POST, "/roles/{nome}/composites", nome).body(roles).retrieve().toBodilessEntity());
    }

    public void removerCompostas(String nome, List<KcRole> roles) {
        if (roles.isEmpty()) {
            return;
        }
        executar(() -> admin(HttpMethod.DELETE, "/roles/{nome}/composites", nome).body(roles).retrieve().toBodilessEntity());
    }

    // ---------- Usuarios ----------

    public List<KcUsuario> listarUsuarios(String busca) {
        String filtro = busca == null || busca.isBlank() ? "" : busca.trim();
        return executar(() -> admin(HttpMethod.GET, "/users?briefRepresentation=false&first=0&max=500&search={busca}", filtro)
                .retrieve().body(LISTA_USUARIOS));
    }

    public KcUsuario buscarUsuario(String id) {
        return executar(() -> admin(HttpMethod.GET, "/users/{id}", id).retrieve().body(KcUsuario.class));
    }

    public Optional<KcUsuario> buscarPorLogin(String login) {
        List<KcUsuario> achados = executar(() -> admin(HttpMethod.GET, "/users?exact=true&username={login}", login)
                .retrieve().body(LISTA_USUARIOS));
        return achados == null ? Optional.empty() : achados.stream().findFirst();
    }

    /** Cria o usuario e devolve o id gerado pelo Keycloak (vem no header Location). */
    public String criarUsuario(String login, String nome, String sobrenome, String email) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("username", login);
        corpo.put("firstName", nome);
        corpo.put("lastName", sobrenome);
        corpo.put("email", email);
        corpo.put("enabled", true);
        corpo.put("emailVerified", true);
        URI local = executar(() -> admin(HttpMethod.POST, "/users").body(corpo).retrieve().toBodilessEntity())
                .getHeaders().getLocation();
        if (local == null) {
            throw new IllegalStateException("Keycloak criou o usuario " + login + " mas nao devolveu o id");
        }
        String caminho = local.getPath();
        return caminho.substring(caminho.lastIndexOf('/') + 1);
    }

    public void atualizarUsuario(String id, String nome, String sobrenome, String email, boolean ativo) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("firstName", nome);
        corpo.put("lastName", sobrenome);
        corpo.put("email", email);
        corpo.put("enabled", ativo);
        executar(() -> admin(HttpMethod.PUT, "/users/{id}", id).body(corpo).retrieve().toBodilessEntity());
    }

    public void definirSenha(String id, String senha, boolean temporaria) {
        executar(() -> admin(HttpMethod.PUT, "/users/{id}/reset-password", id)
                .body(Map.of("type", "password", "value", senha, "temporary", temporaria))
                .retrieve().toBodilessEntity());
    }

    /** Roles atribuidas DIRETAMENTE ao usuario (perfis e permissoes avulsas). */
    public List<KcRole> rolesDiretas(String id) {
        return executar(() -> admin(HttpMethod.GET, "/users/{id}/role-mappings/realm", id).retrieve().body(LISTA_ROLES));
    }

    /** Roles efetivas: diretas + tudo que vem expandido dos perfis (roles compostas). */
    public List<KcRole> rolesEfetivas(String id) {
        return executar(() -> admin(HttpMethod.GET, "/users/{id}/role-mappings/realm/composite", id).retrieve().body(LISTA_ROLES));
    }

    public void adicionarRoles(String id, List<KcRole> roles) {
        if (roles.isEmpty()) {
            return;
        }
        executar(() -> admin(HttpMethod.POST, "/users/{id}/role-mappings/realm", id).body(roles).retrieve().toBodilessEntity());
    }

    public void removerRoles(String id, List<KcRole> roles) {
        if (roles.isEmpty()) {
            return;
        }
        executar(() -> admin(HttpMethod.DELETE, "/users/{id}/role-mappings/realm", id).body(roles).retrieve().toBodilessEntity());
    }

    // ---------- Infra ----------

    private RestClient.RequestBodySpec admin(HttpMethod metodo, String caminho, Object... variaveis) {
        return http.method(metodo)
                .uri("/admin/realms/" + props.realm() + caminho, variaveis)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private synchronized String token() {
        if (tokenAtual != null && Instant.now().isBefore(tokenExpiraEm)) {
            return tokenAtual;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        TokenResposta resposta = executar(() -> http.post()
                .uri("/realms/" + props.realm() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResposta.class));
        if (resposta == null || resposta.access_token() == null) {
            throw new IllegalStateException("Keycloak nao devolveu token para o client " + props.clientId());
        }
        tokenAtual = resposta.access_token();
        // Renova 30s antes de vencer, pra nao mandar token expirando no meio da chamada
        tokenExpiraEm = Instant.now().plusSeconds(Math.max(0, resposta.expires_in() - 30));
        return tokenAtual;
    }

    private <T> T executar(Supplier<T> chamada) {
        try {
            return chamada.get();
        } catch (HttpClientErrorException.Conflict e) {
            throw new IllegalArgumentException("Ja existe um usuario ou role com esses dados (login ou e-mail repetido)", e);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Registro nao encontrado no Keycloak", e);
        } catch (HttpClientErrorException e) {
            String corpo = e.getResponseBodyAsString();
            if (corpo.contains("error-person-name-invalid-character")) {
                throw new IllegalArgumentException(
                        "Nome ou sobrenome com caractere invalido: use so letras, espacos, hifen e apostrofo", e);
            }
            if (corpo.contains("error-username-invalid-character")) {
                throw new IllegalArgumentException(
                        "Login com caractere invalido: use so letras sem acento, numeros, ponto, hifen, sublinhado ou @, sem espacos", e);
            }
            if (corpo.contains("invalid-email") || corpo.contains("invalidEmail")) {
                throw new IllegalArgumentException("E-mail invalido", e);
            }
            throw new IllegalArgumentException("Keycloak recusou a operacao: " + corpo, e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Falha ao falar com o Keycloak em " + props.urlInterna(), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KcRole(String id, String name, String description, boolean composite) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KcUsuario(String id, String username, String firstName, String lastName, String email,
                            boolean enabled, Long createdTimestamp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResposta(String access_token, long expires_in) {
    }
}
