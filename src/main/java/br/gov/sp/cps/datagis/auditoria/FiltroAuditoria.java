package br.gov.sp.cps.datagis.auditoria;

import br.gov.sp.cps.datagis.auditoria.DescricaoAcao.Acao;
import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio.RegistroAuditoria;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Grava em {@code log_auditoria} toda requisicao que ALTERA dado (POST/PUT/PATCH/DELETE)
 * na API: quem, o que, quando, de onde e com qual resultado (LIZARDS-48).
 *
 * <p>Fica DENTRO da cadeia do Spring Security, logo antes do {@code ExceptionTranslationFilter}
 * (ver {@code SecurityConfig}): o usuario do token ja foi identificado quando a requisicao
 * passa aqui, e a recusa por falta de permissao (403) ainda acontece "dentro" deste filtro -
 * entao tentativa negada tambem fica registrada, nao so o que deu certo.
 *
 * <p>Nao e {@code @Component} de proposito: se fosse, o Spring Boot registraria o filtro
 * uma segunda vez na cadeia de servlet e cada acao seria gravada duas vezes.
 *
 * <p>Falha ao gravar o log nao derruba a requisicao do usuario - vira erro no log da
 * aplicacao. O corpo da requisicao nunca e lido (senha temporaria, uploads de GB).
 */
public class FiltroAuditoria extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroAuditoria.class);
    private static final Set<String> METODOS_QUE_ALTERAM = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final LogAuditoriaRepositorio repositorio;

    public FiltroAuditoria(LogAuditoriaRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String rota = request.getRequestURI();
        boolean rotaDaApi = rota.startsWith("/api/") || rota.equals("/conjuntos") || rota.startsWith("/conjuntos/");
        return !METODOS_QUE_ALTERAM.contains(request.getMethod()) || !rotaDaApi;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant inicio = Instant.now();
        // O usuario ja foi resolvido pelo filtro do token, antes deste
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        boolean falhou = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            falhou = true;
            throw e;
        } finally {
            int status = falhou && response.getStatus() < 400 ? 500 : response.getStatus();
            registrar(request, autenticacao, status, inicio);
        }
    }

    private void registrar(HttpServletRequest request, Authentication autenticacao, int status, Instant inicio) {
        try {
            String rota = request.getRequestURI();
            Optional<Acao> acao = DescricaoAcao.identificar(request.getMethod(), rota);
            String usuarioId = null;
            String login = null;
            String perfis = null;
            if (autenticacao instanceof JwtAuthenticationToken jwt) {
                usuarioId = jwt.getToken().getSubject();
                login = jwt.getName();
                perfis = jwt.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith("perfil-"))
                        .sorted()
                        .collect(Collectors.joining(","));
            }
            repositorio.registrar(new RegistroAuditoria(
                    inicio,
                    usuarioId,
                    login,
                    perfis,
                    request.getMethod(),
                    rota,
                    acao.map(Acao::rotaPadrao).orElse(null),
                    request.getQueryString(),
                    acao.map(Acao::descricao).orElse(request.getMethod() + " " + rota),
                    status,
                    status < 400,
                    Instant.now().toEpochMilli() - inicio.toEpochMilli(),
                    ipDeOrigem(request)));
        } catch (RuntimeException e) {
            log.error("Falha ao gravar log de auditoria de {} {}", request.getMethod(), request.getRequestURI(), e);
        }
    }

    private static String ipDeOrigem(HttpServletRequest request) {
        String encaminhado = request.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank()) {
            return encaminhado.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
