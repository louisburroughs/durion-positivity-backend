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

import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.UserNotAwaitingActivationException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.AdministratorActivationService;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
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
 * Slice test for {@link PlatformAdministratorController} (ADR-0062 §7, WS2b-3): the mint endpoint
 * is behind {@code platform:tenant:provision}, answers 403 {@code PLATFORM_TENANT_REQUIRED} for a
 * non-platform binding (the service's refusal, mapped by {@code GlobalExceptionHandler}), 404
 * {@code USER_NOT_FOUND} for a user the tenant does not hold, and returns the token once with 201.
 */
@WebMvcTest(PlatformAdministratorController.class)
@Import(PlatformAdministratorControllerTest.SliceTestConfig.class)
class PlatformAdministratorControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final UUID USER = UUID.fromString("01990000-0000-7000-8000-000000000501");
    private static final String PATH = "/v1/platform/tenants/{tenantId}/administrators/{userId}/activation-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdministratorActivationService activationService;

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
    @DisplayName("platform:tenant:provision → 201 with the token and its expiry, shown once")
    void mintsWithThePermission() throws Exception {
        when(activationService.mint(TENANT, USER))
                .thenReturn(new AdministratorActivationService.IssuedToken(
                        "Qm9iIGlzIG5vdCBhIHJlYWwgdG9rZW4gYnV0IGxvb2tzIGxpa2Ugb25l",
                        Instant.parse("2026-09-13T12:00:00Z")));

        mockMvc.perform(post(PATH, TENANT, USER)
                        .with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("Qm9iIGlzIG5vdCBhIHJlYWwgdG9rZW4gYnV0IGxvb2tzIGxpa2Ugb25l"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-13T12:00:00Z"));
    }

    @Test
    @DisplayName("unauthenticated → 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(post(PATH, TENANT, USER)).andExpect(status().isUnauthorized());
        verify(activationService, never()).mint(any(), any());
    }

    @Test
    @DisplayName("a tenant administrator (security:user:edit, ROLE_ADMIN) lacks the permission → 403")
    void tenantAdministratorIs403() throws Exception {
        mockMvc.perform(post(PATH, TENANT, USER)
                        .with(user("admin.alpha").roles("ADMIN").authorities(() -> "security:user:edit")))
                .andExpect(status().isForbidden());
        verify(activationService, never()).mint(any(), any());
    }

    @Test
    @DisplayName("the permission held under a non-platform binding → 403 PLATFORM_TENANT_REQUIRED envelope")
    void nonPlatformBindingIs403() throws Exception {
        when(activationService.mint(TENANT, USER)).thenThrow(new PlatformTenantRequiredException(TENANT));

        mockMvc.perform(post(PATH, TENANT, USER)
                        .header("X-Correlation-Id", "corr-ws2b-3")
                        .with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Correlation-Id", "corr-ws2b-3"))
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").value("corr-ws2b-3"));
    }

    @Test
    @DisplayName("a user the tenant does not hold → 404 USER_NOT_FOUND")
    void unknownUserIs404() throws Exception {
        when(activationService.mint(TENANT, USER)).thenThrow(new UserNotFoundException("User not found: " + USER));

        mockMvc.perform(post(PATH, TENANT, USER)
                        .with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("a user that is not awaiting activation → 409 USER_NOT_AWAITING_ACTIVATION")
    void liveUserIs409() throws Exception {
        when(activationService.mint(TENANT, USER)).thenThrow(new UserNotAwaitingActivationException(USER));

        mockMvc.perform(post(PATH, TENANT, USER)
                        .with(user("admin.platform").authorities(() -> "platform:tenant:provision")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NOT_AWAITING_ACTIVATION"));
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
