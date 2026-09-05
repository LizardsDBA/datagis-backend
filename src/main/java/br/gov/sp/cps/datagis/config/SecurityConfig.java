package br.gov.sp.cps.datagis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuração provisória de segurança — libera todos os endpoints.
 *
 * A dependência spring-boot-starter-security fica no projeto (ver CLAUDE.md §5.3) para
 * não precisar reconfigurar o Maven quando o RBAC de verdade entrar. Sem este bean,
 * o Spring Security bloqueia (401) qualquer requisição, mesmo em rotas públicas.
 *
 * TODO: RN008 - remover/substituir quando o RBAC (Administrador/Operador/Analista/
 * Auditor) for implementado.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
