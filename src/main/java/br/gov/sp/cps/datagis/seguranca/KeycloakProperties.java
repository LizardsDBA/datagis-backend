package br.gov.sp.cps.datagis.seguranca;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades {@code app.keycloak.*} (ver application.properties).
 *
 * @param urlPublica   endereco que o navegador usa; vira o issuer do token
 * @param urlInterna   endereco que o backend usa pra falar com o Keycloak
 * @param realm        realm do sistema ({@code datagis})
 * @param clientId     client confidencial do backend (conta de servico)
 * @param clientSecret segredo desse client
 * @param provisionar  sincronizar roles/perfis do codigo com o Keycloak na subida
 * @param usuariosDev  criar um usuario de teste por perfil (so desenvolvimento)
 */
@ConfigurationProperties("app.keycloak")
public record KeycloakProperties(
        String urlPublica,
        String urlInterna,
        String realm,
        String clientId,
        String clientSecret,
        boolean provisionar,
        boolean usuariosDev) {
}
