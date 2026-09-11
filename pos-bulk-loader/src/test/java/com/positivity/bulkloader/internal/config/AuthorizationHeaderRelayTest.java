package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.bulkloader.internal.security.GatewayCallerHeaders;
import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * The outbound headers of a bulk-ingest or resolution call (ADR-0062, plan WS8): the bound tenant
 * travels as {@code X-Tenant-Id} beside the operator's credentials, and an unbound thread sends no
 * tenant header rather than a made-up one.
 */
@DisplayName("AuthorizationHeaderRelay: tenant and credential headers on outbound calls")
class AuthorizationHeaderRelayTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final BulkLoadAuthorizationContext authorizationContext = new BulkLoadAuthorizationContext();
    private final AuthorizationHeaderRelay relay = new AuthorizationHeaderRelay(authorizationContext);

    @AfterEach
    void clear() {
        TenantContext.clear();
        authorizationContext.clear();
    }

    @Test
    @DisplayName("the bound tenant is sent as X-Tenant-Id with the relayed bearer token")
    void sendsTheBoundTenantWithTheCredentials() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        authorizationContext.setAuthorizationHeader("Bearer token-1");
        TenantContext.bind(TENANT);

        relay.apply(spec);

        verify(spec).header(TenantHeaders.HTTP_TENANT_ID, TENANT.toString());
        verify(spec).header(HttpHeaders.AUTHORIZATION, "Bearer token-1");
        verify(spec).header(GatewaySecurityConstants.HEADER_TOKEN, "token-1");
    }

    @Test
    @DisplayName("an unbound thread sends no tenant header (and no credential when none is known)")
    void sendsNoTenantHeaderWhenUnbound() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);

        relay.apply(spec);

        verify(spec, never()).header(eq(TenantHeaders.HTTP_TENANT_ID), anyString());
        verify(spec, never()).header(eq(HttpHeaders.AUTHORIZATION), anyString());
    }

    @Test
    @DisplayName("the caller's gateway authorities travel with the call, or the sibling sees nobody")
    void sendsTheCapturedGatewayAuthorities() {
        // The sibling is called directly, not through the gateway, so GatewayAuthoritiesFilter has
        // only these headers to authenticate with. Without them a protected lookup answers 401,
        // and RestResolutionContext reports that as an unresolved business key rather than as a
        // refusal — the rows load with their references silently missing.
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        authorizationContext.setAuthorizationHeader("Bearer token-1");
        authorizationContext.setGatewayHeaders(Map.of(
                GatewaySecurityConstants.HEADER_AUTHORITIES, "location:read,crm:party:create",
                GatewaySecurityConstants.HEADER_USER, "admin.alpha",
                GatewaySecurityConstants.HEADER_PERM_BITS, "AQID",
                GatewaySecurityConstants.HEADER_PERM_VER, "7"));
        TenantContext.bind(TENANT);

        relay.apply(spec);

        HttpHeaders sent = applyHeaderCustomisations(spec, new HttpHeaders());
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_AUTHORITIES))
                .isEqualTo("location:read,crm:party:create");
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_USER)).isEqualTo("admin.alpha");
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_PERM_BITS)).isEqualTo("AQID");
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_PERM_VER)).isEqualTo("7");
        verify(spec).header(HttpHeaders.AUTHORIZATION, "Bearer token-1");
    }

    @Test
    @DisplayName("a header the call site already set is left alone: an ingest writer's narrower authority wins")
    void doesNotOverwriteAHeaderTheCallSiteAlreadySet() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        authorizationContext.setGatewayHeaders(Map.of(
                GatewaySecurityConstants.HEADER_AUTHORITIES, "location:read,crm:party:create",
                GatewaySecurityConstants.HEADER_USER, "admin.alpha"));
        TenantContext.bind(TENANT);

        relay.apply(spec);

        HttpHeaders alreadySet = new HttpHeaders();
        alreadySet.set(GatewaySecurityConstants.HEADER_AUTHORITIES, "catalog:product:create");
        HttpHeaders sent = applyHeaderCustomisations(spec, alreadySet);
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_AUTHORITIES))
                .as("BulkIngestWriterFactory's per-target authority is not widened by the relay")
                .isEqualTo("catalog:product:create");
        assertThat(sent.get(GatewaySecurityConstants.HEADER_AUTHORITIES))
                .as("and it is set, never appended to")
                .hasSize(1);
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_USER))
                .as("a header nobody set is still filled in")
                .isEqualTo("admin.alpha");
    }

    @Test
    @DisplayName("an explicit X-Authorities override also holds back the compact perm-bitset headers,"
            + " which GatewayAuthoritiesFilter would otherwise decode instead of it")
    void withholdsPermBitsWhenTheCallSiteAlreadySetAuthorities() {
        // GatewayAuthoritiesFilter checks X-Perm-Bits first and, when present, never even looks at
        // X-Authorities — it decodes the operator's whole permission set from the bitset instead.
        // Gap-filling X-Perm-Bits in behind BulkIngestWriterFactory's narrower X-Authorities would
        // therefore still let the operator's bitset win on the sibling side (Copilot review of
        // PR #1955, fifth round).
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        authorizationContext.setGatewayHeaders(Map.of(
                GatewaySecurityConstants.HEADER_AUTHORITIES, "location:read,crm:party:create",
                GatewaySecurityConstants.HEADER_PERM_BITS, "AQID",
                GatewaySecurityConstants.HEADER_PERM_VER, "7",
                GatewaySecurityConstants.HEADER_LOC_SCOPE, "loc-scope-bits",
                GatewaySecurityConstants.HEADER_USER, "admin.alpha"));
        TenantContext.bind(TENANT);

        relay.apply(spec);

        HttpHeaders alreadySet = new HttpHeaders();
        alreadySet.set(GatewaySecurityConstants.HEADER_AUTHORITIES, "catalog:product:create");
        HttpHeaders sent = applyHeaderCustomisations(spec, alreadySet);
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_AUTHORITIES))
                .as("the call site's narrower authority still wins")
                .isEqualTo("catalog:product:create");
        assertThat(sent.headerNames())
                .as("the compact bitset shape is withheld so the sibling's plain-authorities decode"
                        + " path runs instead of the perm-bits path")
                .doesNotContain(
                        GatewaySecurityConstants.HEADER_PERM_BITS,
                        GatewaySecurityConstants.HEADER_PERM_VER,
                        GatewaySecurityConstants.HEADER_LOC_SCOPE);
        assertThat(sent.getFirst(GatewaySecurityConstants.HEADER_USER))
                .as("a header nobody set is still filled in")
                .isEqualTo("admin.alpha");
    }

    @Test
    @DisplayName("a launch that captured no authorities sends none rather than inventing any")
    void sendsNoGatewayAuthoritiesWhenTheLaunchCapturedNone() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        authorizationContext.setAuthorizationHeader("Bearer token-1");
        TenantContext.bind(TENANT);

        relay.apply(spec);

        assertThat(applyHeaderCustomisations(spec, new HttpHeaders()).headerNames())
                .doesNotContainAnyElementsOf(GatewayCallerHeaders.RELAYED);
        verify(spec).header(HttpHeaders.AUTHORIZATION, "Bearer token-1");
    }

    /** Runs whatever the relay handed to {@code headers(...)} against real {@link HttpHeaders}. */
    private static HttpHeaders applyHeaderCustomisations(RestClient.RequestHeadersSpec<?> spec, HttpHeaders headers) {
        ArgumentCaptor<Consumer<HttpHeaders>> captor = ArgumentCaptor.captor();
        verify(spec, atLeast(0)).headers(captor.capture());
        captor.getAllValues().forEach(consumer -> consumer.accept(headers));
        return headers;
    }

    @Test
    @DisplayName("the tenant is sent even when no credential can be found")
    void sendsTheTenantWithoutACredential() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        TenantContext.bind(TENANT);

        relay.apply(spec);

        verify(spec).header(TenantHeaders.HTTP_TENANT_ID, TENANT.toString());
        verify(spec, never()).header(eq(HttpHeaders.AUTHORIZATION), anyString());
    }
}
