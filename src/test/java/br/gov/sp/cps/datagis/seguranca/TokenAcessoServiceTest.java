package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.seguranca.KeycloakAdminClient.KcRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenAcessoServiceTest {

    private static final KcRole ADMIN = new KcRole("r1", "perfil-administrador", null, true);
    private static final KcRole ANALISTA = new KcRole("r2", "perfil-analista", null, true);

    private final TokenAcessoRepositorio repositorio = mock(TokenAcessoRepositorio.class);
    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final TokenAcessoService service = new TokenAcessoService(repositorio, keycloak);

    @Test
    void ligaParaAdministrador() {
        when(keycloak.rolesDiretas("u1")).thenReturn(List.of(ADMIN));

        service.definir("u1", true, "admin");

        verify(repositorio).definir("u1", true, "admin");
    }

    @Test
    void naoLigaParaQuemNaoEAdministrador() {
        when(keycloak.rolesDiretas("u1")).thenReturn(List.of(ANALISTA));

        assertThatThrownBy(() -> service.definir("u1", true, "admin"))
                .hasMessage(TokenAcessoService.MENSAGEM_SO_ADMINISTRADOR);
        verify(repositorio, never()).definir(anyString(), anyBoolean(), anyString());
    }

    @Test
    void desligarValeParaQualquerUm() {
        when(keycloak.rolesDiretas("u1")).thenReturn(List.of(ANALISTA));

        service.definir("u1", false, "admin");

        verify(repositorio).definir("u1", false, "admin");
    }

    @Test
    void liberacaoGravadaSoValeEnquantoForAdministrador() {
        when(repositorio.habilitado("u1")).thenReturn(true);

        assertThat(service.ativo("u1", List.of("perfil-administrador"))).isTrue();
        assertThat(service.ativo("u1", List.of("perfil-analista"))).isFalse();
    }

    @Test
    void tirarOAdministradorDesligaOToken() {
        when(keycloak.rolesDiretas("u1")).thenReturn(List.of(ANALISTA));
        when(repositorio.habilitado("u1")).thenReturn(true);

        service.desligarSeNaoAdministrador("u1", "admin");

        verify(repositorio).definir("u1", false, "admin");
    }

    @Test
    void cadastroComTokenExigeSoOPerfilAdministrador() {
        assertThatThrownBy(() -> service.validarCadastroComToken(List.of("perfil-analista")))
                .hasMessage(TokenAcessoService.MENSAGEM_SO_ADMINISTRADOR);
        assertThatThrownBy(() -> service.validarCadastroComToken(null))
                .hasMessage(TokenAcessoService.MENSAGEM_SO_ADMINISTRADOR);
        assertThatCode(() -> service.validarCadastroComToken(List.of("perfil-administrador")))
                .doesNotThrowAnyException();
    }
}
