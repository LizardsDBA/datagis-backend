package br.gov.sp.cps.datagis.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class MudancaAuditadaTest {

    @AfterEach
    void limpar() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void guardaOAntesEDepoisNaRequisicaoComoJson() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Map<String, Object> antes = new LinkedHashMap<>();
        antes.put("email", "a@x.com");
        antes.put("perfis", List.of("perfil-auditor"));

        MudancaAuditada.registrar(antes, Map.of("email", "b@x.com"));

        assertThat(MudancaAuditada.antes(request)).isEqualTo("{\"email\":\"a@x.com\",\"perfis\":[\"perfil-auditor\"]}");
        assertThat(MudancaAuditada.depois(request)).isEqualTo("{\"email\":\"b@x.com\"}");
    }

    @Test
    void cadastroNovoNaoTemAntes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        MudancaAuditada.registrar(null, Map.of("login", "maria"));

        assertThat(MudancaAuditada.antes(request)).isNull();
        assertThat(MudancaAuditada.depois(request)).contains("maria");
    }

    @Test
    void antesEDepoisIguaisNaoSaoGravados() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        MudancaAuditada.registrar(Map.of("perfis", List.of("perfil-auditor")), Map.of("perfis", List.of("perfil-auditor")));

        assertThat(MudancaAuditada.antes(request)).isNull();
        assertThat(MudancaAuditada.depois(request)).isNull();
    }

    @Test
    void foraDeUmaRequisicaoNaoFazNada() {
        MudancaAuditada.registrar(Map.of("a", 1), Map.of("a", 2));
        // sem excecao: job agendado ou teste sem requisicao HTTP
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }
}
