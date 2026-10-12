package br.gov.sp.cps.datagis.seguranca;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcRole;
import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcUsuario;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeycloakProvisionadorTest {

    @Mock
    KeycloakAdminClient keycloak;

    @Test
    void keycloakVazioRecebeTodasAsRolesEOPerfilCompleto() {
        when(keycloak.listarRoles()).thenReturn(List.of(), todasAsRolesDoCatalogo());
        when(keycloak.compostasDaRole("perfil-administrador")).thenReturn(List.of());

        provisionador(false).sincronizar();

        for (Permissao p : Permissao.values()) {
            verify(keycloak).criarRole(eq(p.codigo()), anyString());
        }
        verify(keycloak).criarRole(eq("perfil-administrador"), anyString());
        verify(keycloak).adicionarCompostas(eq("perfil-administrador"),
                argThat(roles -> roles.size() == Permissao.values().length));
        verify(keycloak, never()).criarUsuario(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void permissaoQueSaiuDoPerfilERemovidaDaComposicao() {
        List<KcRole> roles = todasAsRolesDoCatalogo();
        KcRole antiga = new KcRole("id-x", "permissao:que-saiu", null, false);
        when(keycloak.listarRoles()).thenReturn(roles);
        List<KcRole> compostasAtuais = new ArrayList<>(roles.stream().filter(r -> Permissao.ehPermissao(r.name())).toList());
        compostasAtuais.add(antiga);
        when(keycloak.compostasDaRole("perfil-administrador")).thenReturn(compostasAtuais);

        provisionador(false).sincronizar();

        verify(keycloak, never()).criarRole(anyString(), anyString());
        verify(keycloak).adicionarCompostas("perfil-administrador", List.of());
        verify(keycloak).removerCompostas("perfil-administrador", List.of(antiga));
    }

    @Test
    void criaUsuarioDeTesteSoSeAindaNaoExistir() {
        when(keycloak.listarRoles()).thenReturn(todasAsRolesDoCatalogo());
        when(keycloak.compostasDaRole(anyString())).thenReturn(List.of());
        when(keycloak.buscarPorLogin("admin")).thenReturn(Optional.empty());
        when(keycloak.criarUsuario("admin", "Administrador", "Teste", "admin@datagis.local")).thenReturn("id-admin");

        provisionador(true).sincronizar();

        verify(keycloak).definirSenha("id-admin", "admin", false);
        verify(keycloak).adicionarRoles(eq("id-admin"), argThat(r -> r.size() == 1 && r.get(0).name().equals("perfil-administrador")));
    }

    @Test
    void naoMexeEmUsuarioDeTesteQueJaExiste() {
        when(keycloak.listarRoles()).thenReturn(todasAsRolesDoCatalogo());
        when(keycloak.compostasDaRole(anyString())).thenReturn(List.of());
        when(keycloak.buscarPorLogin("admin"))
                .thenReturn(Optional.of(new KcUsuario("id-admin", "admin", "A", "B", "a@b", true, 0L)));

        provisionador(true).sincronizar();

        verify(keycloak, never()).criarUsuario(anyString(), anyString(), anyString(), anyString());
        verify(keycloak, never()).definirSenha(anyString(), anyString(), anyBoolean());
        verify(keycloak, never()).adicionarRoles(eq("id-admin"), any());
    }

    private KeycloakProvisionador provisionador(boolean usuariosDev) {
        KeycloakProperties props = new KeycloakProperties("http://pub", "http://int", "datagis", "datagis-backend",
                "segredo", true, usuariosDev);
        return new KeycloakProvisionador(keycloak, props);
    }

    private static List<KcRole> todasAsRolesDoCatalogo() {
        List<KcRole> roles = new ArrayList<>();
        Arrays.stream(Permissao.values())
                .forEach(p -> roles.add(new KcRole("id-" + p.codigo(), p.codigo(), p.descricao(), false)));
        Arrays.stream(Perfil.values())
                .forEach(p -> roles.add(new KcRole("id-" + p.codigo(), p.codigo(), p.nome() + " - " + p.descricao(), true)));
        return roles;
    }
}
