package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.TenantNotFoundException;
import com.positivity.securityservice.internal.exception.TenantNotImpersonableException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.PlatformImpersonationService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link PlatformImpersonationController} (ADR-0062 §7, WS2b-4): the endpoint is
 * behind {@code platform:tenant:impersonate}, answers 403 {@code PLATFORM_TENANT_REQUIRED} for a
 * non-platform binding, 404 {@code TENANT_NOT_FOUND} and 409 {@code TENANT_NOT_IMPERSONABLE} as
 * the service refuses, and returns the token once with 201.
 */
@WebMvcTest(PlatformImpersonationController.class)
@Import(PlatformImpersonationControllerTest.SliceTestConfig.class)
class PlatformImpersonationControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final String PATH = "/v1/platform/tenants/{tenantId}/impersonation-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlatformImpersonationService impersonationService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void configureJwtFilterPassthrough() throws Exception {
        doAnswer(inv -> {
                    ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(jwtAuthenticationFilter)
                .doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("platform:tenant:impersonate → 201 with the token, expiry, tenant id and slug, shown once")
    void mintsWithThePermission() throws Exception {
        when(impersonationService.issue(eq(TENANT), eq("corr-ws2b-4")))
                .thenReturn(new PlatformImpersonationService.IssuedToken(
                        "signed.jwt.token", Instant.parse("2026-09-10T12:15:00Z"), TENANT, "acme"));

        mockMvc.perform(post(PATH, TENANT)
                        .header("X-Correlation-Id", "corr-ws2b-4")
                        .with(user("admin.platform").authorities(() -> "platform:tenant:impersonate")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("signed.jwt.token"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-10T12:15:00Z"))
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.tenantSlug").value("acme"));
    }

    @Test
    @DisplayName("no correlation header → the service receives null")
    void noCorrelationHeader() throws Exception {
        when(impersonationService.issue(eq(TENANT), isNull()))
                .thenReturn(new PlatformImpersonationService.IssuedToken(
                        "signed.jwt.token", Instant.parse("2026-09-10T12:15:00Z"), TENANT, "acme"));

        mockMvc.perform(post(PATH, TENANT)
                        .with(user("admin.platform").authorities(() -> "platform:tenant:impersonate")))
                .andExpect(status().isCreated());
        verify(impersonationService).issue(TENANT, null);
    }

    @Test
    @DisplayName("unauthenticated → 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(post(PATH, TENANT)).andExpect(status().isUnauthorized());
        verify(impersonationService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("platform:tenant:provision alone, or a tenant administrator, lacks the permission → 403")
    void otherAuthoritiesAre403() throws Exception {
        mockMvc.perform(post(PATH, TENANT).with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH, TENANT)
                        .with(user("admin.alpha").roles("ADMIN").authorities(() -> "security:user:edit")))
                .andExpect(status().isForbidden());
        verify(impersonationService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("the permission held under a non-platform binding → 403 PLATFORM_TENANT_REQUIRED envelope")
    void nonPlatformBindingIs403() throws Exception {
        when(impersonationService.issue(eq(TENANT), any())).thenThrow(new PlatformTenantRequiredException(TENANT));

        mockMvc.perform(post(PATH, TENANT)
                        .header("X-Correlation-Id", "corr-ws2b-4")
                        .with(user("admin.platform").authorities(() -> "platform:tenant:impersonate")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Correlation-Id", "corr-ws2b-4"))
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").value("corr-ws2b-4"));
    }

    @Test
    @DisplayName("a tenant the replica does not know → 404 TENANT_NOT_FOUND")
    void unknownTenantIs404() throws Exception {
        when(impersonationService.issue(eq(TENANT), any())).thenThrow(new TenantNotFoundException(TENANT));

        mockMvc.perform(post(PATH, TENANT)
                        .with(user("admin.platform").authorities(() -> "platform:tenant:impersonate")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a suspended tenant → 409 TENANT_NOT_IMPERSONABLE naming the status")
    void inactiveTenantIs409() throws Exception {
        when(impersonationService.issue(eq(TENANT), any()))
                .thenThrow(new TenantNotImpersonableException(TENANT, "its status is SUSPENDED"));

        mockMvc.perform(post(PATH, TENANT)
                        .with(user("admin.platform").authorities(() -> "platform:tenant:impersonate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_IMPERSONABLE"))
                .andExpect(jsonPath("$.message")
                        .value("Tenant " + TENANT + " cannot be impersonated: its status is SUSPENDED"));
    }

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
            return http.build();
        }
    }
}
