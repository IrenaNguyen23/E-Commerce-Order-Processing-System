package com.commerceflow.paymentservice.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/** OpenAPI descriptor exposed at {@code /v3/api-docs} and rendered at {@code /swagger-ui.html}. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Value("${commerceflow.openapi.public-url:http://localhost:8080}")
    private String publicUrl;

    @Bean
    public OpenAPI paymentServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CommerceFlow Payment Service API")
                        .version("1.0.0")
                        .description("Charges the customer once stock has been reserved and publishes the payment outcome that decides whether the order completes or compensates.")
                        .contact(new Contact().name("CommerceFlow Platform Team")
                                .email("platform@commerceflow.io"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url(publicUrl).description("API Gateway"),
                        new Server().url("http://localhost:8084").description("Direct")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token returned by POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
