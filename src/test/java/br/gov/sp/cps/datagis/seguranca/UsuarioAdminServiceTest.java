package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcRole;
import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcUsuario;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioAtualizacaoRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioNovoRequest;
import br.gov.sp.cps.datagis.seguranca.UsuarioDtos.UsuarioResponse;
import java.util.List;
import java.util.Set;
import br.gov.sp.cps.datagis.auditoria.MudancaAuditadaAcesso;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsuarioAdminServiceTest {

    private static final KcRole ADMIN = role("perfil-administrador");
    private static final KcRole FONTE_VER = role("fonte:visualizar");
    private static final KcRole LOTE_GERENCIAR = role("lote:gerenciar");
    private static final KcRole USUARIO_GERENCIAR = role("usuario:gerenciar");
    private static final KcRole INTERNA = role("default-roles-datagis");

    @Mock
    KeycloakAdminClient keycloak;

    UsuarioAdminService service;

    MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void limparRequisicao() {
        RequestContextHolder.resetRequestAttributes();
    }

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        service = new UsuarioAdminService(keycloak);
        when(keycloak.listarRoles()).thenReturn(List.of(ADMIN, FONTE_VER, LOTE_GERENCIAR, USUARIO_GERENCIAR, INTERNA));
        when(keycloak.buscarUsuario(anyString()))
                .thenReturn(new KcUsuario("u1", "maria", "Maria", "Silva", "maria@x.com", true, 0L));
        when(keycloak.rolesDiretas(anyString())).thenReturn(List.of());
        when(keycloak.rolesEfetivas(anyString())).thenReturn(List.of());
    }

    @Test
    void criaComSenhaTemporariaEAtribuiAsRoles() {
        when(keycloak.criarUsuario("maria", "Maria", "Silva", "maria@x.com")).thenReturn("u1");

        service.criar(new UsuarioNovoRequest(" maria ", "Maria", "Silva", "maria@x.com", "Senha@123",
                List.of("perfil-administrador", "lote:gerenciar")));

        verify(keycloak).definirSenha("u1", "Senha@123", true);
        verify(keycloak).adicionarRoles("u1", List.of(ADMIN, LOTE_GERENCIAR));
    }

    @Test
    void recusaRoleForaDoCatalogoAntesDeCriarQualquerCoisa() {
        assertThatThrownBy(() -> service.criar(new UsuarioNovoRequest("maria", "Maria", "Silva", "maria@x.com",
                "Senha@123", List.of("perfil-inexistente"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perfil-inexistente");

        verify(keycloak, never()).criarUsuario(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void definirRolesAdicionaOQueFaltaRemoveOQueSaiuENaoMexeEmRoleInterna() {
        when(keycloak.rolesDiretas("u1")).thenReturn(List.of(FONTE_VER, LOTE_GERENCIAR, INTERNA));

        service.definirRoles("u1", List.of("fonte:visualizar", "perfil-administrador"), "outro-admin");

        verify(keycloak).adicionarRoles("u1", List.of(ADMIN));
        verify(keycloak).removerRoles("u1", List.of(LOTE_GERENCIAR));
    }

    @Test
    void naoDeixaOAdminTirarDeSiMesmoAGestaoDeUsuarios() {
        assertThatThrownBy(() -> service.definirRoles("u1", List.of("fonte:visualizar"), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usuario:gerenciar");

        verify(keycloak, never()).removerRoles(anyString(), any());
    }

    @Test
    void adminPodeTrocarAsPropriasRolesSeContinuarComGestaoDeUsuariosViaPerfil() {
        service.definirRoles("u1", List.of("perfil-administrador"), "u1");

        verify(keycloak).adicionarRoles("u1", List.of(ADMIN));
    }

    @Test
    void naoDeixaDesativarOProprioUsuario() {
        assertThatThrownBy(() -> service.atualizar("u1",
                new UsuarioAtualizacaoRequest("Maria", "Silva", "maria@x.com", false), "u1"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(keycloak, never()).atualizarUsuario(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void respostaSeparaPerfisDePermissoesAvulsasEMostraAsEfetivas() {
        when(keycloak.rolesDiretas("u1")).thenReturn(List.of(ADMIN, LOTE_GERENCIAR, INTERNA));
        when(keycloak.rolesEfetivas("u1")).thenReturn(List.of(INTERNA, USUARIO_GERENCIAR, FONTE_VER, ADMIN, LOTE_GERENCIAR));

        UsuarioResponse resposta = service.buscar("u1");

        assertThat(resposta.perfis()).containsExactly("perfil-administrador");
        assertThat(resposta.permissoesAvulsas()).containsExactly("lote:gerenciar");
        // Ordem do catalogo (enum), nao a ordem em que o Keycloak devolveu
        assertThat(resposta.permissoesEfetivas()).containsExactly("fonte:visualizar", "lote:gerenciar", "usuario:gerenciar");
    }

    @Test
    void perfilAdministradorExpandeParaTodasAsPermissoes() {
        assertThat(UsuarioAdminService.permissoesExpandidas(Set.of("perfil-administrador")))
                .hasSize(Permissao.values().length);
    }

    private static KcRole role(String nome) {
        return new KcRole("id-" + nome, nome, null, false);
    }

    @Test
    void editarEntregaOAntesEDepoisProLogDeAuditoria() {
        when(keycloak.buscarUsuario("u1")).thenReturn(
                new KcUsuario("u1", "maria", "Maria", "Silva", "maria@x.com", true, 0L),
                new KcUsuario("u1", "maria", "Maria", "Silva", "maria@novo.com", false, 0L));

        service.atualizar("u1", new UsuarioAtualizacaoRequest("Maria", "Silva", "maria@novo.com", false), "outro");

        assertThat(MudancaAuditadaAcesso.antes(request)).contains("\"email\":\"maria@x.com\"").contains("\"ativo\":true");
        assertThat(MudancaAuditadaAcesso.depois(request)).contains("\"email\":\"maria@novo.com\"").contains("\"ativo\":false");
    }

    @Test
    void cadastroNovoTemSoODepoisESemSenha() {
        when(keycloak.criarUsuario(anyString(), anyString(), anyString(), anyString())).thenReturn("u1");

        service.criar(new UsuarioNovoRequest("maria", "Maria", "Silva", "maria@x.com", "Senha@123", List.of()));

        assertThat(MudancaAuditadaAcesso.antes(request)).isNull();
        assertThat(MudancaAuditadaAcesso.depois(request)).contains("\"login\":\"maria\"").doesNotContain("Senha@123");
    }

    @Test
    void redefinirSenhaNuncaGravaASenhaNoLog() {
        service.redefinirSenha("u1", "Segredo@2026");

        assertThat(MudancaAuditadaAcesso.antes(request)).doesNotContain("Segredo@2026");
        assertThat(MudancaAuditadaAcesso.depois(request))
                .doesNotContain("Segredo@2026")
                .contains("\"trocaObrigatoriaNoProximoLogin\":true");
    }

    @Test
    void falhaAoSalvarNaoRegistraDepois() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Keycloak recusou"))
                .when(keycloak).atualizarUsuario(anyString(), anyString(), anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> service.atualizar("u1",
                new UsuarioAtualizacaoRequest("Maria", "Silva", "maria@x.com", true), "outro"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(MudancaAuditadaAcesso.depois(request)).isNull();
    }

    @Test
    void salvarPerfisSemMudarNadaNaoGravaAntesEDepois() {
        when(keycloak.rolesDiretas(anyString())).thenReturn(List.of(ADMIN));

        service.definirRoles("u1", List.of("perfil-administrador"), "outro");

        assertThat(MudancaAuditadaAcesso.antes(request)).isNull();
        assertThat(MudancaAuditadaAcesso.depois(request)).isNull();
    }
}
