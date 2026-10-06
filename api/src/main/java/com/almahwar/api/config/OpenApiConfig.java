package com.almahwar.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the API. springdoc is <b>off</b> by default ({@code springdoc.api-docs.enabled=false}) and
 * switched on only by the {@code dev} profile: production does not publish its API map.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI almahwarOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Al Mahwar Store Management API").version("v1")
                        .description("REST API for the Al Mahwar mobile clients. All business endpoints need a bearer "
                                + "access token from POST /api/v1/auth/login. Errors use one JSON format (ApiError)."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
