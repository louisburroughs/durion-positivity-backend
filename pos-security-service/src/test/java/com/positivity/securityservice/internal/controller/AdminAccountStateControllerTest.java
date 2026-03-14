package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.positivity.securityservice.internal.dto.AccountStateResponse;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.service.AdminAccountStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

/**
 * Controller slice tests for {@link AdminAccountStateController} — AUTH-006.
 *
 * <p>Verifies that the admin account-state endpoints enforce ADMIN authorization,
 * return the correct HTTP status codes (ADR-0017), and route to the service layer.
 * All service calls are mocked — no database or full application context is loaded.
 *
 * Issue: AUTH-006
 */
@WebMvcTest(AdminAccountStateController.class)
@Import(AdminAccountStateControllerTest.SliceTestConfig.class)
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.INHERIT)
@DisplayName("AdminAccountStateControllerTest — AUTH-006")
class AdminAccountStateControllerTest {

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAccountStateService adminAccountStateService;

    // Security infrastructure beans required by SecurityConfig
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    /**
     * Make the mocked JWT filter a passthrough so controller-level
     * {@code .with(user(...))} security context injection is not disrupted.
     */
    @BeforeEach
    void configureJwtFilterPassthrough() throws Exception {
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // ── Unlock ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /v1/users/{id}/unlock")
    class UnlockEndpoint {

        /**
         * ADR-0017: successful void mutation returns 204 No Content.
         */
        @Test
        @DisplayName("ADMIN POST /v1/users/{id}/unlock → 204 No Content")
        void unlock_adminRole_returns204() throws Exception {
            doNothing().when(adminAccountStateService).unlock(USER_ID);

            mockMvc.perform(post("/v1/users/{id}/unlock", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("unauthenticated POST /v1/users/{id}/unlock → 401 Unauthorized")
        void unlock_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/unlock", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("non-ADMIN POST /v1/users/{id}/unlock → 403 Forbidden")
        void unlock_nonAdminRole_returns403() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/unlock", USER_ID).with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── Enable ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /v1/users/{id}/enable")
    class EnableEndpoint {

        /**
         * ADR-0017: successful void mutation returns 204 No Content.
         */
        @Test
        @DisplayName("ADMIN POST /v1/users/{id}/enable → 204 No Content")
        void enable_adminRole_returns204() throws Exception {
            doNothing().when(adminAccountStateService).enable(USER_ID);

            mockMvc.perform(post("/v1/users/{id}/enable", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("unauthenticated POST /v1/users/{id}/enable → 401 Unauthorized")
        void enable_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/enable", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("non-ADMIN POST /v1/users/{id}/enable → 403 Forbidden")
        void enable_nonAdminRole_returns403() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/enable", USER_ID).with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── Disable ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /v1/users/{id}/disable")
    class DisableEndpoint {

        /**
         * ADR-0017: disable has no request body — actor is resolved server-side
         * from security context (ADR-0018).
         */
        @Test
        @DisplayName("ADMIN POST /v1/users/{id}/disable (no body) → 204 No Content")
        void disable_adminRole_returns204() throws Exception {
            doNothing().when(adminAccountStateService).disable(USER_ID);

            mockMvc.perform(post("/v1/users/{id}/disable", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("unauthenticated POST /v1/users/{id}/disable → 401 Unauthorized")
        void disable_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/disable", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("non-ADMIN POST /v1/users/{id}/disable → 403 Forbidden")
        void disable_nonAdminRole_returns403() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/disable", USER_ID).with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ExpireAccount ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /v1/users/{id}/expire-account")
    class ExpireAccountEndpoint {

        /**
         * ADR-0017: successful void mutation returns 204 No Content.
         */
        @Test
        @DisplayName("ADMIN POST /v1/users/{id}/expire-account → 204 No Content")
        void expireAccount_adminRole_returns204() throws Exception {
            doNothing().when(adminAccountStateService).expireAccount(USER_ID);

            mockMvc.perform(post("/v1/users/{id}/expire-account", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("unauthenticated POST /v1/users/{id}/expire-account → 401 Unauthorized")
        void expireAccount_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/expire-account", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("non-ADMIN POST /v1/users/{id}/expire-account → 403 Forbidden")
        void expireAccount_nonAdminRole_returns403() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/expire-account", USER_ID).with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ExpireCredentials ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /v1/users/{id}/expire-credentials")
    class ExpireCredentialsEndpoint {

        /**
         * ADR-0017: successful void mutation returns 204 No Content.
         */
        @Test
        @DisplayName("ADMIN POST /v1/users/{id}/expire-credentials → 204 No Content")
        void expireCredentials_adminRole_returns204() throws Exception {
            doNothing().when(adminAccountStateService).expireCredentials(USER_ID);

            mockMvc.perform(post("/v1/users/{id}/expire-credentials", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("unauthenticated POST /v1/users/{id}/expire-credentials → 401 Unauthorized")
        void expireCredentials_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/expire-credentials", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("non-ADMIN POST /v1/users/{id}/expire-credentials → 403 Forbidden")
        void expireCredentials_nonAdminRole_returns403() throws Exception {
            mockMvc.perform(post("/v1/users/{id}/expire-credentials", USER_ID).with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── GetAccountState ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /v1/users/{id}/account-state")
    class GetAccountStateEndpoint {

        /**
         * ADR-0017: successful read returns 200 OK with JSON body.
         */
        @Test
        @DisplayName("ADMIN GET /v1/users/{id}/account-state → 200 with JSON body containing userId")
        void getAccountState_adminRole_returns200WithUserId() throws Exception {
            AccountStateResponse response = AccountStateResponse.builder()
                    .userId(USER_ID)
                    .enabled(true)
                    .accountNonLocked(true)
                    .accountNonExpired(true)
                    .credentialsNonExpired(true)
                    .failedLoginAttempts(0)
                    .build();
            when(adminAccountStateService.getAccountState(USER_ID)).thenReturn(response);

                mockMvc.perform(get("/v1/users/{id}/account-state", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()));
        }

        /**
         * ADR-0017: missing resource returns 404 Not Found.
         */
        @Test
        @DisplayName("ADMIN GET /v1/users/{unknown-id}/account-state when not found → 404")
        void getAccountState_userNotFound_returns404() throws Exception {
            when(adminAccountStateService.getAccountState(USER_ID))
                    .thenThrow(new UserNotFoundException("User not found: " + USER_ID));

            mockMvc.perform(get("/v1/users/{id}/account-state", USER_ID).with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isNotFound());
        }

        /**
         * ADR-0017: insufficient authority returns 403 Forbidden.
         */
        @Test
        @DisplayName("VIEWER GET /v1/users/{id}/account-state → 403 Forbidden")
        void getAccountState_viewerRole_returns403() throws Exception {
            mockMvc.perform(get("/v1/users/{id}/account-state", USER_ID).with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }

        /**
         * ADR-0017: missing credentials returns 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated GET /v1/users/{id}/account-state → 401 Unauthorized")
        void getAccountState_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/v1/users/{id}/account-state", USER_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── Test slice configuration ──────────────────────────────────────────────

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        /** Provides the Clock bean required by {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler}. */
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
            return http.build();
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /**
     * Minimal security exception handler for the MVC slice — translates Spring Security
     * exceptions to 401/403 HTTP status codes. {@code @Order(HIGHEST_PRECEDENCE)} ensures
     * this advice is consulted before the production GlobalExceptionHandler catch-all.
     */
    @ControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException(AuthenticationException ex) {
            // Status mapping is fully expressed via @ResponseStatus.
        }

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDeniedException(AccessDeniedException ex) {
            // Status mapping is fully expressed via @ResponseStatus.
        }
    }
}
