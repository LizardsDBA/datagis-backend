package br.gov.sp.cps.datagis.seguranca;

import br.gov.sp.cps.datagis.auditoria.MudancaAuditada;
import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcRole;
import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcUsuario;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.CatalogoAcessoResponse;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.PerfilInfo;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.PermissaoInfo;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioAtualizacaoRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioNovoRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Cadastro de usuarios e atribuicao de perfis/permissoes. Nao ha tabela de usuario no
 * Oracle: a identidade mora no Keycloak e este servico so traduz a tela pra Admin API dele.
 *
 * <p>So mexe nas roles do catalogo ({@link Perfil} e {@link Permissao}); roles internas do
 * Keycloak (ex.: {@code default-roles-datagis}, {@code offline_access}) nunca sao
 * adicionadas nem removidas por aqui.
 *
 * <p>Toda alteracao entrega o antes e depois do usuario ao log de auditoria
 * ({@link MudancaAuditada}), sem senha.
 *
 * <p>Trava de seguranca: o administrador nao consegue desativar a si mesmo nem tirar de si
 * a permissao {@code usuario:gerenciar} - senao o sistema pode ficar sem ninguem capaz de
 * administrar usuarios (so restaria o console do Keycloak).
 */
@Service
public class UsuarioAdminService {

    private final KeycloakAdminClient keycloak;

    public UsuarioAdminService(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    public CatalogoAcessoResponse catalogo() {
        List<PerfilInfo> perfis = Arrays.stream(Perfil.values())
                .map(p -> new PerfilInfo(p.codigo(), p.nome(), p.descricao(), ordenar(codigos(p.permissoes()))))
                .toList();
        List<PermissaoInfo> permissoes = Arrays.stream(Permissao.values())
                .map(p -> new PermissaoInfo(p.codigo(), p.descricao()))
                .toList();
        return new CatalogoAcessoResponse(perfis, permissoes);
    }

    public List<UsuarioResponse> listar(String busca) {
        return keycloak.listarUsuarios(busca).stream()
                .sorted(Comparator.comparing(KcUsuario::username))
                .map(this::montarResposta)
                .toList();
    }

    public UsuarioResponse buscar(String id) {
        return montarResposta(keycloak.buscarUsuario(id));
    }

    public UsuarioResponse criar(UsuarioNovoRequest dados) {
        List<String> roles = dados.roles() == null ? List.of() : dados.roles();
        validarRolesDoCatalogo(roles);
        String id = keycloak.criarUsuario(dados.login().trim(), dados.nome().trim(), dados.sobrenome().trim(),
                dados.email().trim());
        keycloak.definirSenha(id, dados.senhaTemporaria(), true);
        keycloak.adicionarRoles(id, resolverRoles(roles));
        UsuarioResponse criado = buscar(id);
        MudancaAuditada.registrar(null, fotografia(criado));
        return criado;
    }

    public UsuarioResponse atualizar(String id, UsuarioAtualizacaoRequest dados, String idSolicitante) {
        if (id.equals(idSolicitante) && !dados.ativo()) {
            throw new IllegalArgumentException("Voce nao pode desativar o seu proprio usuario");
        }
        UsuarioResponse antes = buscar(id);
        keycloak.atualizarUsuario(id, dados.nome().trim(), dados.sobrenome().trim(), dados.email().trim(), dados.ativo());
        UsuarioResponse depois = buscar(id);
        MudancaAuditada.registrar(fotografia(antes), fotografia(depois));
        return depois;
    }

    /**
     * Substitui os perfis e permissoes avulsas do usuario pela lista recebida: adiciona o
     * que falta e remove o que nao veio. Roles fora do catalogo ficam como estao.
     */
    public UsuarioResponse definirRoles(String id, List<String> roles, String idSolicitante) {
        validarRolesDoCatalogo(roles);
        Set<String> desejadas = new HashSet<>(roles);
        if (id.equals(idSolicitante) && !permissoesExpandidas(desejadas).contains(Permissao.USUARIO_GERENCIAR.codigo())) {
            throw new IllegalArgumentException(
                    "Voce nao pode remover de si mesmo a permissao usuario:gerenciar (ficaria sem acesso a esta tela)");
        }
        List<KcRole> atuaisDoCatalogo = keycloak.rolesDiretas(id).stream()
                .filter(r -> ehDoCatalogo(r.name()))
                .toList();
        Set<String> nomesAtuais = atuaisDoCatalogo.stream().map(KcRole::name).collect(Collectors.toSet());

        List<String> adicionar = desejadas.stream().filter(r -> !nomesAtuais.contains(r)).toList();
        List<KcRole> remover = atuaisDoCatalogo.stream().filter(r -> !desejadas.contains(r.name())).toList();

        UsuarioResponse antes = buscar(id);
        keycloak.adicionarRoles(id, resolverRoles(adicionar));
        keycloak.removerRoles(id, remover);
        UsuarioResponse depois = buscar(id);
        MudancaAuditada.registrar(fotografia(antes), fotografia(depois));
        return depois;
    }

    public void redefinirSenha(String id, String senhaTemporaria) {
        keycloak.definirSenha(id, senhaTemporaria, true);
        // A senha nunca vai pro log: so o fato de ter sido trocada
        Map<String, Object> antes = new LinkedHashMap<>();
        antes.put("senha", "anterior");
        antes.put("trocaObrigatoriaNoProximoLogin", null);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("senha", "nova senha temporaria definida (o valor nao e gravado)");
        depois.put("trocaObrigatoriaNoProximoLogin", true);
        MudancaAuditada.registrar(antes, depois);
    }

    /** O que vai pro log de auditoria: so dado de cadastro e acesso, nada derivado nem secreto. */
    static Map<String, Object> fotografia(UsuarioResponse u) {
        Map<String, Object> foto = new LinkedHashMap<>();
        foto.put("login", u.login());
        foto.put("nome", u.nome());
        foto.put("sobrenome", u.sobrenome());
        foto.put("email", u.email());
        foto.put("ativo", u.ativo());
        foto.put("perfis", u.perfis());
        foto.put("permissoesAvulsas", u.permissoesAvulsas());
        return foto;
    }

    private UsuarioResponse montarResposta(KcUsuario u) {
        List<String> diretas = keycloak.rolesDiretas(u.id()).stream().map(KcRole::name).toList();
        List<String> efetivas = keycloak.rolesEfetivas(u.id()).stream().map(KcRole::name).toList();
        return new UsuarioResponse(
                u.id(),
                u.username(),
                u.firstName(),
                u.lastName(),
                u.email(),
                u.enabled(),
                u.createdTimestamp() == null ? null : Instant.ofEpochMilli(u.createdTimestamp()),
                ordenar(diretas.stream().filter(Perfil::ehPerfil).toList()),
                ordenar(diretas.stream().filter(Permissao::ehPermissao).toList()),
                ordenar(efetivas.stream().filter(Permissao::ehPermissao).toList()));
    }

    private void validarRolesDoCatalogo(List<String> roles) {
        List<String> desconhecidas = roles.stream().filter(r -> !ehDoCatalogo(r)).toList();
        if (!desconhecidas.isEmpty()) {
            throw new IllegalArgumentException("Perfil/permissao desconhecido: " + desconhecidas);
        }
    }

    private List<KcRole> resolverRoles(List<String> nomes) {
        if (nomes.isEmpty()) {
            return List.of();
        }
        Map<String, KcRole> porNome = new HashMap<>();
        keycloak.listarRoles().forEach(r -> porNome.put(r.name(), r));
        return nomes.stream().map(nome -> {
            KcRole role = porNome.get(nome);
            if (role == null) {
                throw new IllegalStateException(
                        "Role " + nome + " ainda nao existe no Keycloak - o catalogo e sincronizado na subida do backend");
            }
            return role;
        }).toList();
    }

    /** Permissoes que um conjunto de perfis + permissoes avulsas daria ao usuario. */
    static Set<String> permissoesExpandidas(Set<String> roles) {
        Set<String> resultado = new HashSet<>();
        for (String role : roles) {
            if (Permissao.ehPermissao(role)) {
                resultado.add(role);
            }
            for (Perfil perfil : Perfil.values()) {
                if (perfil.codigo().equals(role)) {
                    resultado.addAll(codigos(perfil.permissoes()));
                }
            }
        }
        return resultado;
    }

    private static boolean ehDoCatalogo(String role) {
        return Perfil.ehPerfil(role) || Permissao.ehPermissao(role);
    }

    private static List<String> codigos(Set<Permissao> permissoes) {
        return permissoes.stream().map(Permissao::codigo).toList();
    }

    /** Ordena pela ordem do catalogo (enum), nao alfabetica - fica igual em toda tela. */
    private static List<String> ordenar(List<String> roles) {
        List<String> ordemCatalogo = new ArrayList<>();
        Arrays.stream(Perfil.values()).forEach(p -> ordemCatalogo.add(p.codigo()));
        Arrays.stream(Permissao.values()).forEach(p -> ordemCatalogo.add(p.codigo()));
        return roles.stream().sorted(Comparator.comparingInt(ordemCatalogo::indexOf)).toList();
    }
}
