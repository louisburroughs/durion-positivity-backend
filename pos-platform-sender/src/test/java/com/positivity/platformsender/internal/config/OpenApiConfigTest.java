package com.positivity.platformsender.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.platformsender.internal.security.SenderSecretFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

/**
 * The spec header documents how the send API is really reached: directly, with the shared secret,
 * never through the gateway with a bearer token.
 */
class OpenApiConfigTest {

    private final OpenAPI openApi = new OpenApiConfig().customOpenAPI();

    @Test
    void documentsTheSharedSecretAsTheOnlySecurityScheme() {
        assertThat(openApi.getComponents().getSecuritySchemes()).containsOnlyKeys(OpenApiConfig.SENDER_SECRET_SCHEME);
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.SENDER_SECRET_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getName()).isEqualTo(SenderSecretFilter.SECRET_HEADER);
        assertThat(openApi.getSecurity())
                .singleElement()
                .satisfies(requirement -> assertThat(requirement).containsOnlyKeys(OpenApiConfig.SENDER_SECRET_SCHEME));
    }

    @Test
    void namesTheDirectContainerAddressNotAGatewayRoute() {
        assertThat(openApi.getServers())
                .singleElement()
                .satisfies(server -> assertThat(server.getUrl()).isEqualTo("http://pos-platform-sender:8080"));
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Positivity Platform Sender API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
    }
}
