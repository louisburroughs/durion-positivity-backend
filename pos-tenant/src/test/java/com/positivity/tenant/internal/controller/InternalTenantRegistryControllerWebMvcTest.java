package com.positivity.tenant.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.security.common.GatewayAuthoritiesFilter;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenant.internal.config.SecurityConfig;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.security.TenantRegistrySecretFilter;
import com.positivity.tenant.internal.service.TenantService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract of the internal registry endpoint through the production security chains:
 * the shared secret is the only way in, the response is the {@code TenantProjectionV1} shape, and
 * the query runs bound to the platform tenant.
 */
@WebMvcTest(InternalTenantRegistryController.class)
@Import({SecurityConfig.class, InternalTenantRegistryControllerWebMvcTest.SliceConfig.class})
@TestPropertySource(properties = "pos.tenant.registry.api-secret=registry-s3cret")
class InternalTenantRegistryControllerWebMvcTest {

    private static final UUID ACME = UUID.fromString("01990000-0000-7000-8000-000000000a01");
    private static final UUID BOLT = UUID.fromString("01990000-0000-7000-8000-000000000b02");

    @TestConfiguration
    static class SliceConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        }

        /** Keep GatewayAuthoritiesFilter inside its chain, as PlatformControllersWebMvcTest does. */
        @Bean
        FilterRegistrationBean<GatewayAuthoritiesFilter> gatewayAuthoritiesFilterRegistration(
                GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantService tenantService;

    private static TenantResponse tenant(UUID id, String slug, String displayName, TenantStatus status) {
        return TenantResponse.builder()
                .id(id)
                .slug(slug)
                .displayName(displayName)
                .status(status)
                .accountId(UUID.fromString("01990000-0000-7000-8000-00000000a001"))
                .initialAdminEmail("owner@" + slug + ".example")
                .build();
    }

    @Test
    void refusesWithoutTheSecret() throws Exception {
        mockMvc.perform(get("/internal/v1/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value(TenantRegistrySecretFilter.CODE_SECRET_INVALID))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(tenantService);
    }

    @Test
    void refusesAWrongSecretAndGatewayHeadersAlone() throws Exception {
        mockMvc.perform(get("/internal/v1/tenants").header(TenantRegistrySecretFilter.SECRET_HEADER, "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(TenantRegistrySecretFilter.CODE_SECRET_INVALID));
        // The gateway's user headers mean nothing here: this chain knows only the secret.
        mockMvc.perform(get("/internal/v1/tenants")
                        .header("X-User", "platform-admin")
                        .header("X-Authorities", "platform:tenant:read"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tenantService);
    }

    @Test
    void listsActiveTenantsAsTheProjectionBoundToThePlatformTenant() throws Exception {
        AtomicReference<UUID> boundTenant = new AtomicReference<>();
        when(tenantService.list(TenantStatus.ACTIVE)).thenAnswer(invocation -> {
            boundTenant.set(TenantContext.current().orElse(null));
            return List.of(
                    tenant(ACME, "acme", "Acme Tire", TenantStatus.ACTIVE),
                    tenant(BOLT, "bolt", "Bolt Auto", TenantStatus.ACTIVE));
        });

        mockMvc.perform(get("/internal/v1/tenants").header(TenantRegistrySecretFilter.SECRET_HEADER, "registry-s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tenantId").value(ACME.toString()))
                .andExpect(jsonPath("$[0].slug").value("acme"))
                .andExpect(jsonPath("$[0].displayName").value("Acme Tire"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].tenantId").value(BOLT.toString()))
                .andExpect(jsonPath("$[0].accountId").doesNotExist())
                .andExpect(jsonPath("$[0].initialAdminEmail").doesNotExist());

        assertThat(boundTenant.get()).isEqualTo(PlatformTenant.ID);
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void statusFilterIsPassedThrough() throws Exception {
        when(tenantService.list(any())).thenReturn(List.of(tenant(ACME, "acme", "Acme", TenantStatus.SUSPENDED)));

        mockMvc.perform(get("/internal/v1/tenants")
                        .param("status", "SUSPENDED")
                        .header(TenantRegistrySecretFilter.SECRET_HEADER, "registry-s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUSPENDED"));

        verify(tenantService).list(TenantStatus.SUSPENDED);
    }
}
