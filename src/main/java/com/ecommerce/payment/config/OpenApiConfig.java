package com.ecommerce.payment.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.*;

import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("Payment Service API").version("1.0.0"))
                // Relative, so "Try it out" from the gateway's unified Swagger UI goes
                // through the gateway instead of to this instance's own IP:port.
                .servers(List.of(new Server().url("/").description("Same origin as this document")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
