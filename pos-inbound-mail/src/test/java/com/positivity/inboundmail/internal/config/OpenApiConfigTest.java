package com.positivity.inboundmail.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inboundmail.internal.security.InboundMailSecretFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

/**
 * The spec header documents how the edge API is really reached: directly, with the shared secret,
 * never through the gateway with a bearer token.
 */
class OpenApiConfigTest {

    private final OpenAPI openApi = new OpenApiConfig().customOpenAPI();

    @Test
    void documentsTheSharedSecretAsTheOnlySecurityScheme() {
        assertThat(openApi.getComponents().getSecuritySchemes()).containsOnlyKeys(OpenApiConfig.INBOUND_MAIL_SECRET_SCHEME);
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.INBOUND_MAIL_SECRET_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getName()).isEqualTo(InboundMailSecretFilter.SECRET_HEADER);
        assertThat(openApi.getSecurity())
                .singleElement()
                .satisfies(requirement -> assertThat(requirement).containsOnlyKeys(OpenApiConfig.INBOUND_MAIL_SECRET_SCHEME));
    }

    @Test
    void namesTheDirectContainerAddressNotAGatewayRoute() {
        assertThat(openApi.getServers())
                .singleElement()
                .satisfies(server -> assertThat(server.getUrl()).isEqualTo("http://pos-inbound-mail:8080"));
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Positivity Inbound Mail API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
    }
}
