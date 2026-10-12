package br.gov.sp.cps.datagis.seguranca;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Transforma o JWT do Keycloak no usuario autenticado do Spring: cada realm role em
 * {@code realm_access.roles} vira uma authority com o MESMO nome (sem prefixo
 * {@code ROLE_}), pra que a regra seja escrita igual ao catalogo:
 * {@code hasAuthority("fonte:visualizar")}.
 *
 * <p>As roles compostas (perfis) ja chegam expandidas pelo Keycloak, entao um usuario com
 * {@code perfil-administrador} recebe aqui todas as permissoes do perfil. O nome do
 * usuario autenticado e o {@code preferred_username} (login), nao o UUID do {@code sub}.
 */
public class ConversorRolesKeycloak implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String nome = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, extrairRoles(jwt), nome != null ? nome : jwt.getSubject());
    }

    static Collection<GrantedAuthority> extrairRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
    }
}
