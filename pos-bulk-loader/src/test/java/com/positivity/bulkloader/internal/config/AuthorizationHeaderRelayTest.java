package com.positivity.bulkloader.internal.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("the tenant is sent even when no credential can be found")
    void sendsTheTenantWithoutACredential() {
        RestClient.RequestHeadersSpec<?> spec = mock(RestClient.RequestHeadersSpec.class);
        TenantContext.bind(TENANT);

        relay.apply(spec);

        verify(spec).header(TenantHeaders.HTTP_TENANT_ID, TENANT.toString());
        verify(spec, never()).header(eq(HttpHeaders.AUTHORIZATION), anyString());
    }
}
