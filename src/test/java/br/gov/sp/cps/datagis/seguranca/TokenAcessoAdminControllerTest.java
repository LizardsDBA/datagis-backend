package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.auditoria.MudancaAuditadaAcesso;
import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcUsuario;
import br.gov.sp.cps.datagis.seguranca.TokenAcessoAdminController.TokenAcessoRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class TokenAcessoAdminControllerTest {

    private final TokenAcessoService tokens = mock(TokenAcessoService.class);
    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final TokenAcessoAdminController controller = new TokenAcessoAdminController(tokens, keycloak);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private static final Jwt ADMIN = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
            Map.of("sub", "uuid-admin", "preferred_username", "admin"));

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(keycloak.buscarUsuario("u1")).thenReturn(new KcUsuario("u1", "maria", "Maria", "Silva", "m@x.com", true, 0L));
    }

    @AfterEach
    void limpar() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void mostraSeEstaLiberado() {
        when(tokens.ativo("u1")).thenReturn(true);

        assertThat(controller.buscar("u1").habilitado()).isTrue();
    }

    @Test
    void liberaGuardandoQuemLiberouEOAntesEDepois() {
        when(tokens.ativo("u1")).thenReturn(false);

        var resposta = controller.definir("u1", new TokenAcessoRequest(true), ADMIN);

        assertThat(resposta.habilitado()).isTrue();
        verify(tokens).definir("u1", true, "admin");
        assertThat(MudancaAuditadaAcesso.antes(request)).contains("\"login\":\"maria\"").contains("\"tokenAcessoHabilitado\":false");
        assertThat(MudancaAuditadaAcesso.depois(request)).contains("\"tokenAcessoHabilitado\":true");
    }
}
