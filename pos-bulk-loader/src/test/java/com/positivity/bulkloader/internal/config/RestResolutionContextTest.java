package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * One business-key lookup, from the resolver down to the headers that leave the process
 * (ADR-0062 plan WS8, and the credential relay this sits on).
 *
 * <p>Resolution is the quiet half of a bulk load: {@code RestResolutionContext} turns a 401 into
 * {@link Optional#empty()} exactly like a 404, so a lookup the sibling refuses does not read as a
 * refusal anywhere — the row simply loads with its reference missing. The protection against that
 * is not in the resolver, it is in the call carrying the operator's authorities in the first
 * place, which is what this pins.
 */
@DisplayName("RestResolutionContext: a resolution lookup carries the operator's credentials")
class RestResolutionContextTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID JOB_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final BulkLoadAuthorizationContext authorizationContext = new BulkLoadAuthorizationContext();
    private final AuthorizationHeaderRelay relay = new AuthorizationHeaderRelay(authorizationContext);

    @AfterEach
    void clear() {
        TenantContext.clear();
        authorizationContext.clear();
    }

    @Test
    @DisplayName("a location lookup goes out authenticated as the operator, in the job's tenant")
    void aLookupCarriesTheOperatorsAuthoritiesAndTenant() {
        RestClient.Builder builder = mock(RestClient.Builder.class, Answers.RETURNS_SELF);
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.get()).thenAnswer(invocation -> uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(invocation -> headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("CLT-MAIN-001");

        // What the launch captured off the operator's request through the gateway.
        authorizationContext.setAuthorizationHeader("Bearer token-seed");
        authorizationContext.setGatewayHeaders(Map.of(
                GatewaySecurityConstants.HEADER_AUTHORITIES, "location:read,bulkImport:upload:execute",
                GatewaySecurityConstants.HEADER_USER, "admin.alpha"));
        TenantContext.bind(TENANT);

        RestResolutionContext resolution = new RestResolutionContext(builder, relay, JOB_LOCATION);
        Optional<String> resolved = resolution.get("LOCATION", "/v1/locations?code=CLT-MAIN-001", String.class);

        assertThat(resolved).contains("CLT-MAIN-001");
        verify(builder).baseUrl("http://LOCATION");
        verify(uriSpec).uri("/v1/locations?code=CLT-MAIN-001");
        verify(headersSpec).header(HttpHeaders.AUTHORIZATION, "Bearer token-seed");
        verify(headersSpec).header(TenantHeaders.HTTP_TENANT_ID, TENANT.toString());

        ArgumentCaptor<Consumer<HttpHeaders>> captor = ArgumentCaptor.captor();
        verify(headersSpec).headers(captor.capture());
        HttpHeaders sent = new HttpHeaders();
        captor.getValue().accept(sent);
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_AUTHORITIES))
                .as("without this the sibling's GatewayAuthoritiesFilter authenticates nobody and answers 401,"
                        + " which this resolver would report as an unresolved name")
                .isEqualTo("location:read,bulkImport:upload:execute");
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_USER)).isEqualTo("admin.alpha");
    }
}
