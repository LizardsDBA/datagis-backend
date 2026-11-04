package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.auditoria.MudancaAuditadaAcesso;
import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcUsuario;
import br.gov.sp.cps.datagis.seguranca.MinhaContaService.DadosRequest;
import br.gov.sp.cps.datagis.seguranca.MinhaContaService.TrocaSenhaRequest;
import java.util.List;
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
class MinhaContaServiceTest {

    @Mock
    KeycloakAdminClient keycloak;

    @Mock
    TokenAcessoService tokens;

    MinhaContaService service;

    MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        service = new MinhaContaService(keycloak, new UsuarioAdminService(keycloak), tokens);
        when(keycloak.buscarUsuario("u1")).thenReturn(new KcUsuario("u1", "maria", "Maria", "Silva", "maria@x.com", true, 0L));
        when(keycloak.rolesDiretas(anyString())).thenReturn(List.of());
        when(keycloak.rolesEfetivas(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void limparRequisicao() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void mostraOsDadosEALiberacaoDoToken() {
        when(tokens.ativo(eq("u1"), any())).thenReturn(true);

        var conta = service.buscar("u1");

        assertThat(conta.login()).isEqualTo("maria");
        assertThat(conta.email()).isEqualTo("maria@x.com");
        assertThat(conta.tokenAcessoHabilitado()).isTrue();
    }

    @Test
    void trocaSoONomeEMantemEmailESituacao() {
        when(keycloak.buscarUsuario("u1")).thenReturn(
                new KcUsuario("u1", "maria", "Maria", "Silva", "maria@x.com", true, 0L),
                new KcUsuario("u1", "maria", "Maria Clara", "Souza", "maria@x.com", true, 0L));

        service.atualizarDados("u1", new DadosRequest(" Maria Clara ", " Souza "));

        verify(keycloak).atualizarUsuario("u1", "Maria Clara", "Souza", "maria@x.com", true);
        assertThat(MudancaAuditadaAcesso.antes(request)).contains("\"login\":\"maria\"").contains("Silva");
        assertThat(MudancaAuditadaAcesso.depois(request)).contains("Souza");
    }

    @Test
    void trocaASenhaQuandoASenhaAtualConfere() {
        when(keycloak.senhaConfere("maria", "Atual@2026")).thenReturn(true);

        service.trocarSenha("u1", new TrocaSenhaRequest("Atual@2026", "Nova@2026x"));

        // Nao temporaria: foi a propria pessoa que escolheu
        verify(keycloak).definirSenha("u1", "Nova@2026x", false);
    }

    @Test
    void recusaSenhaAtualErradaSemTrocarNada() {
        when(keycloak.senhaConfere("maria", "Errada@2026")).thenReturn(false);

        assertThatThrownBy(() -> service.trocarSenha("u1", new TrocaSenhaRequest("Errada@2026", "Nova@2026x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Senha atual incorreta");
        verify(keycloak, never()).definirSenha(anyString(), anyString(), anyBoolean());
    }

    @Test
    void recusaNovaSenhaIgualAAtualSemNemConferir() {
        assertThatThrownBy(() -> service.trocarSenha("u1", new TrocaSenhaRequest("Mesma@2026", "Mesma@2026")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diferente");
        verify(keycloak, never()).senhaConfere(anyString(), anyString());
        verify(keycloak, never()).definirSenha(anyString(), anyString(), anyBoolean());
    }

    @Test
    void aSenhaNuncaVaiProLog() {
        when(keycloak.senhaConfere("maria", "Atual@2026")).thenReturn(true);

        service.trocarSenha("u1", new TrocaSenhaRequest("Atual@2026", "Nova@2026x"));

        assertThat(MudancaAuditadaAcesso.antes(request)).doesNotContain("Atual@2026");
        assertThat(MudancaAuditadaAcesso.depois(request)).doesNotContain("Nova@2026x").contains("proprio usuario");
    }
}
