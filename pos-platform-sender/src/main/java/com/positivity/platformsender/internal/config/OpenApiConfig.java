package com.positivity.platformsender.internal.config;

import com.positivity.platformsender.internal.security.SenderSecretFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Header of the documentation-only spec ({@code openapi.yaml}, #2428). The send API is called
 * service to service and never through the gateway, so the spec names the direct address and the
 * shared-secret scheme rather than a gateway route and a bearer token.
 */
@Configuration
public class OpenApiConfig {

    static final String SENDER_SECRET_SCHEME = "senderSecret";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Positivity Platform Sender API")
                        .description("FI-2 shared platform sender: delivers rendered email through Amazon SES and SMS"
                                + " through AWS End User Messaging. Service-to-service only: pos-marketing's"
                                + " PlatformSenderClient is its one caller (ADR-0044 amendment 2026-10-03), and the"
                                + " API Gateway has no route here.")
                        .version("v1")
                        .contact(new Contact().email("platform@durionpos.org").name("Durion Support Services")))
                .servers(List.of(new Server()
                        .url("http://pos-platform-sender:8080")
                        .description("Direct container address (Compose and alpha)")))
                .components(new Components()
                        .addSecuritySchemes(
                                SENDER_SECRET_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(SenderSecretFilter.SECRET_HEADER)
                                        .description("Shared secret, pos.platform-sender.api-secret")))
                .addSecurityItem(new SecurityRequirement().addList(SENDER_SECRET_SCHEME));
    }
}
