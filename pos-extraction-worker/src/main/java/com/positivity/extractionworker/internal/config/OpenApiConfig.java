package com.positivity.extractionworker.internal.config;

import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
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
 * Header of the documentation-only spec ({@code openapi.yaml}, #2428). The worker API is called service to
 * service and never through the gateway, so the spec names the direct address and the shared-secret
 * scheme, and stays out of the gateway aggregate and the SDKs.
 */
@Configuration
public class OpenApiConfig {

    static final String WORKER_SECRET_SCHEME = "workerSecret";
    static final String TENANT_SCHEME = "tenantLabel";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Positivity Extraction Worker API")
                        .description("Stateless worker that opens untrusted bill files (CAP:550 S26). Every parse runs"
                                + " in a child JVM that holds no secret. Service-to-service only: pos-accounting is"
                                + " its one caller, and the API Gateway has no route here.")
                        .version("v1")
                        .contact(new Contact().email("platform@durionpos.org").name("Durion Support Services")))
                .servers(List.of(new Server()
                        .url("http://extraction-proxy:8080")
                        .description("Through extraction-proxy (Compose and alpha); the worker itself is only on extraction-net")))
                .components(new Components()
                        .addSecuritySchemes(
                                WORKER_SECRET_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(WorkerSecretFilter.SECRET_HEADER)
                                        .description("Shared secret, pos.extraction-worker.api-secret"))
                        .addSecuritySchemes(
                                TENANT_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(TenantLabelFilter.TENANT_HEADER)
                                        .description("Tenant UUID: a log and metric label only, never authorisation")))
                .addSecurityItem(
                        new SecurityRequirement().addList(WORKER_SECRET_SCHEME).addList(TENANT_SCHEME));
    }
}
