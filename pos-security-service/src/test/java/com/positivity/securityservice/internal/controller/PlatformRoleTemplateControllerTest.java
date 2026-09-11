package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.RoleTemplateReconcileResponse;
import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.TenantNotFoundException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.RoleTemplateReconciliationService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * Slice test for {@link PlatformRoleTemplateController} (ADR-0062 §6, WS8): the reconcile endpoint
 * is behind {@code platform:tenant:provision}, answers 403 {@code PLATFORM_TENANT_REQUIRED} for a
 * non-platform binding and 404 {@code TENANT_NOT_FOUND} for an unknown tenant (the service's
 * refusals, mapped by {@code GlobalExceptionHandler}), and returns what changed with 200.
 */
@WebMvcTest(PlatformRoleTemplateController.class)
@Import(PlatformRoleTemplateControllerTest.SliceTestConfig.class)
class PlatformRoleTemplateControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final String PATH = "/v1/platform/tenants/{tenantId}/roles/reconcile-template";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleTemplateReconciliationService reconciliationService;

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
    @DisplayName("platform:tenant:provision → 200 with what changed")
    void reconcilesWithThePermission() throws Exception {
        when(reconciliationService.reconcile(TENANT))
                .thenReturn(new RoleTemplateReconcileResponse(
                        TENANT,
                        List.of("WARRANTY_CLERK"),
                        List.of(new RoleTemplateReconcileResponse.GrantAdded("SHOP_MANAGER", "warranty:claim:view")),
                        List.of("DISPATCHER")));

        mockMvc.perform(post(PATH, TENANT).with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.rolesCreated[0]").value("WARRANTY_CLERK"))
                .andExpect(jsonPath("$.grantsAdded[0].role").value("SHOP_MANAGER"))
                .andExpect(jsonPath("$.grantsAdded[0].permission").value("warranty:claim:view"))
                .andExpect(jsonPath("$.templateKeysAssigned[0]").value("DISPATCHER"));
    }

    @Test
    @DisplayName("unauthenticated → 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(post(PATH, TENANT)).andExpect(status().isUnauthorized());
        verify(reconciliationService, never()).reconcile(any());
    }

    @Test
    @DisplayName("a tenant administrator (security:role:edit, ROLE_ADMIN) lacks the permission → 403")
    void tenantAdministratorIs403() throws Exception {
        mockMvc.perform(post(PATH, TENANT)
                        .with(user("admin.alpha").roles("ADMIN").authorities(() -> "security:role:edit")))
                .andExpect(status().isForbidden());
        verify(reconciliationService, never()).reconcile(any());
    }

    @Test
    @DisplayName("the permission held under a non-platform binding → 403 PLATFORM_TENANT_REQUIRED envelope")
    void nonPlatformBindingIs403() throws Exception {
        when(reconciliationService.reconcile(TENANT)).thenThrow(new PlatformTenantRequiredException(TENANT));

        mockMvc.perform(post(PATH, TENANT)
                        .header("X-Correlation-Id", "corr-ws8")
                        .with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Correlation-Id", "corr-ws8"))
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").value("corr-ws8"));
    }

    @Test
    @DisplayName("an unknown tenant → 404 TENANT_NOT_FOUND")
    void unknownTenantIs404() throws Exception {
        when(reconciliationService.reconcile(TENANT)).thenThrow(new TenantNotFoundException(TENANT));

        mockMvc.perform(post(PATH, TENANT).with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
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
