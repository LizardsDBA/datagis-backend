package br.gov.sp.cps.datagis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio;
import br.gov.sp.cps.datagis.seguranca.Perfil;
import br.gov.sp.cps.datagis.seguranca.Permissao;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confere a matriz de acesso do {@link SecurityConfig} sem subir controller de verdade:
 * um controller "pega-tudo" responde 200 em qualquer rota, entao o status so depende da
 * seguranca - 200 = liberado, 403 = sem a permissao, 401 = sem token.
 */
@WebMvcTest(controllers = MatrizAcessoTest.PegaTudoController.class)
@Import({SecurityConfig.class, MatrizAcessoTest.PegaTudoController.class})
class MatrizAcessoTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    // O FiltroAuditoria grava toda requisicao que altera dado; aqui so precisa existir
    @MockitoBean
    LogAuditoriaRepositorio logAuditoria;

    @RestController
    static class PegaTudoController {
        @RequestMapping("/**")
        String ok() {
            return "ok";
        }
    }

    static Stream<Arguments> rotas() {
        return Stream.of(
                Arguments.of("GET", "/api/fontes", Permissao.FONTE_VISUALIZAR),
                Arguments.of("GET", "/api/fontes/1/progresso", Permissao.FONTE_VISUALIZAR),
                Arguments.of("GET", "/api/fontes/1/mapeamentos", Permissao.FONTE_VISUALIZAR),
                Arguments.of("POST", "/api/fontes", Permissao.FONTE_GERENCIAR),
                Arguments.of("PUT", "/api/fontes/1", Permissao.FONTE_GERENCIAR),
                Arguments.of("POST", "/api/fontes/1/executar", Permissao.FONTE_GERENCIAR),
                Arguments.of("POST", "/api/fontes/1/upload", Permissao.FONTE_GERENCIAR),
                Arguments.of("DELETE", "/api/fontes/1/mapeamentos/2", Permissao.FONTE_GERENCIAR),
                Arguments.of("GET", "/api/lotes", Permissao.LOTE_VISUALIZAR),
                Arguments.of("POST", "/api/lotes/1/reprocessar", Permissao.LOTE_GERENCIAR),
                Arguments.of("POST", "/api/quarentena/lotes/1/liberar", Permissao.LOTE_GERENCIAR),
                Arguments.of("POST", "/api/quarentena/lotes/1/descartar", Permissao.LOTE_GERENCIAR),
                Arguments.of("GET", "/api/quarentena", Permissao.QUARENTENA_VISUALIZAR),
                Arguments.of("GET", "/api/quarentena/metricas", Permissao.QUARENTENA_VISUALIZAR),
                Arguments.of("POST", "/api/quarentena/imovel_rural/1/reprocessar", Permissao.QUARENTENA_TRATAR),
                Arguments.of("POST", "/api/quarentena/dominios", Permissao.QUARENTENA_TRATAR),
                Arguments.of("GET", "/api/conjuntos-dados/lotes/1/bronze", Permissao.DADOS_VISUALIZAR),
                Arguments.of("GET", "/conjuntos", Permissao.CONJUNTO_VISUALIZAR),
                Arguments.of("POST", "/conjuntos", Permissao.CONJUNTO_GERENCIAR),
                Arguments.of("GET", "/api/gold/status", Permissao.INDICADOR_VISUALIZAR),
                Arguments.of("GET", "/api/gold/imoveis/PR-1", Permissao.INDICADOR_VISUALIZAR),
                Arguments.of("GET", "/api/indicadores/tipos", Permissao.INDICADOR_VISUALIZAR),
                Arguments.of("POST", "/api/gold/publicar", Permissao.INDICADOR_PUBLICAR),
                Arguments.of("PUT", "/api/indicadores/tipos/1", Permissao.INDICADOR_PUBLICAR),
                Arguments.of("GET", "/api/admin/usuarios", Permissao.USUARIO_GERENCIAR),
                Arguments.of("PUT", "/api/admin/usuarios/abc/roles", Permissao.USUARIO_GERENCIAR),
                Arguments.of("GET", "/api/auditoria", Permissao.AUDITORIA_VISUALIZAR));
    }

    @ParameterizedTest(name = "{0} {1} exige {2}")
    @MethodSource("rotas")
    void liberaComAPermissaoExigida(String metodo, String rota, Permissao exigida) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(metodo), rota).with(comPermissoes(exigida)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} {1} nega sem {2}")
    @MethodSource("rotas")
    void negaComTodasAsOutrasPermissoes(String metodo, String rota, Permissao exigida) throws Exception {
        Permissao[] outras = Arrays.stream(Permissao.values()).filter(p -> p != exigida).toArray(Permissao[]::new);
        mvc.perform(request(HttpMethod.valueOf(metodo), rota).with(comPermissoes(outras)))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> perfisERotas() {
        return Arrays.stream(Perfil.values())
                .flatMap(perfil -> rotas().map(rota -> Arguments.of(perfil, rota.get()[0], rota.get()[1], rota.get()[2])));
    }

    /**
     * Cada perfil (com as permissoes que o Keycloak expande da role composta) acessa a rota
     * se e somente se o pacote dele contem a permissao exigida. Perfil novo entra aqui sozinho.
     */
    @ParameterizedTest(name = "{0}: {1} {2}")
    @MethodSource("perfisERotas")
    void perfilAcessaSoOQueOPacoteDele(Perfil perfil, String metodo, String rota, Permissao exigida) throws Exception {
        int esperado = perfil.permissoes().contains(exigida) ? 200 : 403;
        mvc.perform(request(HttpMethod.valueOf(metodo), rota)
                        .with(comPermissoes(perfil.permissoes().toArray(Permissao[]::new))))
                .andExpect(status().is(esperado));
    }

    @Test
    void auditoriaRegistraATentativaNegadaComOUsuarioDoToken() throws Exception {
        mvc.perform(request(HttpMethod.POST, "/api/quarentena/lotes/45/liberar")
                        .with(jwt().jwt(j -> j.subject("uuid-maria").claim("preferred_username", "maria"))
                                .authorities(new SimpleGrantedAuthority(Permissao.LOTE_VISUALIZAR.codigo()))))
                .andExpect(status().isForbidden());

        verify(logAuditoria).registrar(argThat(r -> r.statusHttp() == 403
                && !r.sucesso()
                && "uuid-maria".equals(r.usuarioId())
                && "Liberar lote para a Silver".equals(r.acao())
                && "/api/quarentena/lotes/{loteId}/liberar".equals(r.rotaPadrao())));
    }

    @Test
    void auditoriaRegistraAAcaoPermitidaENaoRegistraLeitura() throws Exception {
        mvc.perform(request(HttpMethod.POST, "/api/fontes/3/executar").with(comPermissoes(Permissao.FONTE_GERENCIAR)))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/fontes").with(comPermissoes(Permissao.FONTE_VISUALIZAR)))
                .andExpect(status().isOk());

        verify(logAuditoria, times(1)).registrar(argThat(r -> r.sucesso()
                && r.statusHttp() == 200
                && "Executar ingestao da fonte".equals(r.acao())));
    }

    @Test
    void semTokenDevolve401() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/fontes")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthESwaggerSaoPublicos() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/actuator/health")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void rotaSemRegraFicaFechadaAteParaQuemTemTudo() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/rota-que-ninguem-cadastrou").with(comPermissoes(Permissao.values())))
                .andExpect(status().isForbidden());
    }

    /**
     * Rede de seguranca: percorre TODOS os endpoints dos controllers reais e confere que
     * nenhum caiu no denyAll() por esquecimento. Se este teste falhar, a rota nova precisa
     * de uma regra no SecurityConfig (e de uma linha em {@link #rotas()}).
     */
    @Test
    void todoEndpointDosControllersTemRegraDeAcesso() throws Exception {
        List<String> semRegra = new ArrayList<>();
        for (String[] endpoint : endpointsDosControllers()) {
            int status = mvc.perform(request(HttpMethod.valueOf(endpoint[0]), endpoint[1])
                            .with(comPermissoes(Permissao.values())))
                    .andReturn().getResponse().getStatus();
            if (status == 403) {
                semRegra.add(endpoint[0] + " " + endpoint[1]);
            }
        }
        assertThat(semRegra).as("endpoints sem regra no SecurityConfig").isEmpty();
    }

    private static JwtRequestPostProcessor comPermissoes(Permissao... permissoes) {
        return jwt().authorities(Arrays.stream(permissoes)
                .map(p -> new SimpleGrantedAuthority(p.codigo()))
                .toArray(GrantedAuthority[]::new));
    }

    private static List<String[]> endpointsDosControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String[]> endpoints = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("br.gov.sp.cps.datagis")) {
            Class<?> controller = Class.forName(bd.getBeanClassName());
            if (controller == PegaTudoController.class) {
                continue;
            }
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefixo = base != null && base.path().length > 0 ? base.path()[0] : "";
            for (Method metodo : controller.getDeclaredMethods()) {
                RequestMapping mapeamento = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                if (mapeamento == null) {
                    continue;
                }
                String[] caminhos = mapeamento.path().length > 0 ? mapeamento.path() : new String[] {""};
                RequestMethod[] verbos = mapeamento.method().length > 0 ? mapeamento.method() : new RequestMethod[] {RequestMethod.GET};
                for (String caminho : caminhos) {
                    String rota = (prefixo + caminho).replaceAll("\\{[^}]+}", "1");
                    for (RequestMethod verbo : verbos) {
                        endpoints.add(new String[] {verbo.name(), rota});
                    }
                }
            }
        }
        assertThat(endpoints).as("o scanner precisa achar os controllers").hasSizeGreaterThan(30);
        return endpoints;
    }
}
