package com.positivity.inboundmail.internal.config;

import com.positivity.inboundmail.internal.security.InboundMailSecretFilter;
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
 * Header of the documentation-only spec ({@code openapi.yaml}, #2428). The edge API is called service
 * to service and never through the gateway, so the spec names the direct address and the
 * shared-secret scheme rather than a gateway route and a bearer token.
 */
@Configuration
public class OpenApiConfig {

    static final String INBOUND_MAIL_SECRET_SCHEME = "inboundMailSecret";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Positivity Inbound Mail API")
                        .description(
                                "CAP:550 S27 inbound-mail edge: the intake address registry and the staged-attachment"
                                        + " store behind vendor-bills-by-email. Service-to-service only: pos-accounting is its"
                                        + " one caller (ADR-0044 amendment 2026-10-05), and the API Gateway has no route here.")
                        .version("v1")
                        .contact(new Contact().email("platform@durionpos.org").name("Durion Support Services")))
                .servers(List.of(new Server()
                        .url("http://pos-inbound-mail:8080")
                        .description("Direct container address (Compose and alpha)")))
                .components(new Components()
                        .addSecuritySchemes(
                                INBOUND_MAIL_SECRET_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(InboundMailSecretFilter.SECRET_HEADER)
                                        .description("Shared secret, pos.inbound-mail.api-secret")))
                .addSecurityItem(new SecurityRequirement().addList(INBOUND_MAIL_SECRET_SCHEME));
    }
}
