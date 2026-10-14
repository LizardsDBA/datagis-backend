package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ConversorRolesKeycloakTest {

    private final ConversorRolesKeycloak conversor = new ConversorRolesKeycloak();

    @Test
    void realmRolesViramAuthoritiesComOMesmoNome() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("fonte:visualizar", "perfil-administrador"))));

        AbstractAuthenticationToken auth = conversor.convert(jwt);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("fonte:visualizar", "perfil-administrador");
        assertThat(auth.getName()).isEqualTo("maria");
    }

    @Test
    void tokenSemRealmAccessNaoTemPermissaoNenhuma() {
        AbstractAuthenticationToken auth = conversor.convert(jwt(Map.of()));

        assertThat(auth.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f1c-uuid")
                .claim("preferred_username", "maria");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
