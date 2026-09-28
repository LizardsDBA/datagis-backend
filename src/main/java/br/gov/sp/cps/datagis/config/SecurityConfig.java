package br.gov.sp.cps.datagis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuração provisória: libera todos os endpoints, sem login.
 *
 * Sem esse bean o Spring Security bloqueia tudo com 401. Mantém a dependência de
 * security no projeto pra não ter que adicionar de novo quando o login for feito.
 *
 * TODO: trocar por login/perfis de verdade mais pra frente.
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
