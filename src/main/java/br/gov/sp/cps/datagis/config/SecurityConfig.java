package br.gov.sp.cps.datagis.config;

import static br.gov.sp.cps.datagis.seguranca.Permissao.AUDITORIA_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.CONJUNTO_GERENCIAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.CONJUNTO_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.DADOS_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.FONTE_GERENCIAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.FONTE_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.INDICADOR_PUBLICAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.INDICADOR_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.LOTE_GERENCIAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.LOTE_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.QUARENTENA_TRATAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.QUARENTENA_VISUALIZAR;
import static br.gov.sp.cps.datagis.seguranca.Permissao.USUARIO_GERENCIAR;
import static org.springframework.http.HttpMethod.GET;

import br.gov.sp.cps.datagis.auditoria.FiltroAuditoria;
import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio;
import br.gov.sp.cps.datagis.seguranca.ConversorRolesKeycloak;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;

/**
 * Controle de acesso (RBAC, LIZARDS-47). O backend nao tem login: o frontend autentica no
 * Keycloak e manda o JWT em {@code Authorization: Bearer}; aqui so validamos assinatura,
 * validade e issuer do token (propriedades {@code spring.security.oauth2.resourceserver.jwt.*})
 * e liberamos cada rota pela role de permissao ({@code Permissao}) que ela exige.
 *
 * <p>A matriz de acesso fica TODA aqui, por rota, em vez de {@code @PreAuthorize} espalhado
 * pelos controllers - assim da pra revisar quem acessa o que num lugar so. Regras sao
 * avaliadas na ordem: dentro de cada prefixo, o GET (leitura) vem antes da regra geral
 * (escrita). Rota nova sem regra cai no {@code denyAll()} do final: fica fechada pra todo
 * mundo ate alguem decidir a permissao dela (o {@code MatrizAcessoTest} pega isso).
 *
 * <p>Sem estado de sessao e sem CSRF: toda requisicao traz o proprio token, nao ha cookie
 * de sessao pra ser explorado.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LogAuditoriaRepositorio logAuditoria) throws Exception {
        http.csrf(csrf -> csrf.disable())
            // Log de auditoria (LIZARDS-48): depois da leitura do token (sabe quem e o
            // usuario) e antes da traducao de "acesso negado" (registra o 403 tambem)
            .addFilterBefore(new FiltroAuditoria(logAuditoria), ExceptionTranslationFilter.class)
            .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Publico: health do compose/monitoramento, documentacao da API e pagina de erro
                .requestMatchers("/actuator/health/**", "/error").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Fontes de dados, mapeamentos, execucao, upload e progresso
                .requestMatchers(GET, "/api/fontes/**").hasAuthority(FONTE_VISUALIZAR.codigo())
                .requestMatchers("/api/fontes/**").hasAuthority(FONTE_GERENCIAR.codigo())

                // Lotes e pipeline (liberar/descartar lote mora sob /api/quarentena/lotes)
                .requestMatchers(GET, "/api/lotes/**").hasAuthority(LOTE_VISUALIZAR.codigo())
                .requestMatchers("/api/lotes/**").hasAuthority(LOTE_GERENCIAR.codigo())
                .requestMatchers("/api/quarentena/lotes/**").hasAuthority(LOTE_GERENCIAR.codigo())

                // Quarentena e qualidade (inclui cadastro de dominio)
                .requestMatchers(GET, "/api/quarentena/**").hasAuthority(QUARENTENA_VISUALIZAR.codigo())
                .requestMatchers("/api/quarentena/**").hasAuthority(QUARENTENA_TRATAR.codigo())

                // Comparacao Bronze/Silver/Quarentena (so leitura)
                .requestMatchers(GET, "/api/conjuntos-dados/**").hasAuthority(DADOS_VISUALIZAR.codigo())

                // Conjuntos e cobertura (endpoint legado sem prefixo /api)
                .requestMatchers(GET, "/conjuntos/**").hasAuthority(CONJUNTO_VISUALIZAR.codigo())
                .requestMatchers("/conjuntos/**").hasAuthority(CONJUNTO_GERENCIAR.codigo())

                // Indicadores e Gold
                .requestMatchers(GET, "/api/gold/**", "/api/indicadores/**").hasAuthority(INDICADOR_VISUALIZAR.codigo())
                .requestMatchers("/api/gold/**", "/api/indicadores/**").hasAuthority(INDICADOR_PUBLICAR.codigo())

                // Administracao de usuarios e roles
                .requestMatchers("/api/admin/**").hasAuthority(USUARIO_GERENCIAR.codigo())

                // Log de auditoria (so consulta - nao existe escrita pela API)
                .requestMatchers(GET, "/api/auditoria/**").hasAuthority(AUDITORIA_VISUALIZAR.codigo())

                .anyRequest().denyAll())
            .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new ConversorRolesKeycloak())));
        return http.build();
    }
}
