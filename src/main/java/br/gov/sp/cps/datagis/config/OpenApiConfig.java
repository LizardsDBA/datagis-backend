package br.gov.sp.cps.datagis.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Botao "Authorize" no Swagger (/swagger-ui.html): cola-se o access token do Keycloak e
 * todas as chamadas da pagina passam a ir com {@code Authorization: Bearer}. Como pegar um
 * token de teste: docs/controle-de-acesso-rbac.md (client {@code datagis-dev}, so desenvolvimento).
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "DataGIS API"),
        security = @SecurityRequirement(name = "keycloak"))
@SecurityScheme(name = "keycloak", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
