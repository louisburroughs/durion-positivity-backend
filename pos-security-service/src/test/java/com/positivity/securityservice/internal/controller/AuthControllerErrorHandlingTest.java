package com.positivity.securityservice.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.exception.NoRolesAssignedException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.AuthenticationService;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.SelfRegistrationService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof of the <em>login</em> entry point of the one-condition-one-status decision in
 * issue #1725 (ADR-0017 §2 question 1): an account whose credentials are valid but which has no
 * roles assigned is refused with 403 {@code USER_HAS_NO_ROLES} — the same answer
 * {@code POST /v1/auth/refresh} gives for the same condition (see
 * {@link JwtControllerErrorHandlingTest}). {@link NoRolesAssignedException} is thrown from
 * {@code AuthenticationServiceImpl#login} and from {@code JwtServiceImpl#refreshAccessToken}; this
 * class covers the former through {@link AuthController#login}, so that the mapping is proven at
 * both HTTP entry points rather than only at the handler and the refresh endpoint.
 *
 * <p>The envelope is asserted in full: status, {@code code}, the exception's own {@code message},
 * the {@code nextAction} hint telling the caller how to get the account back into service, and the
 * echoed {@code X-Correlation-Id} header (ADR-0017 §4).
 *
 * <p>Slice setup mirrors {@link JwtControllerErrorHandlingTest}: {@code @WebMvcTest} does not
 * auto-register {@code pos-web-common}'s {@code @AutoConfiguration}, so
 * {@link WebCommonErrorAutoConfiguration} is imported explicitly to exercise the real handler
 * chain; {@link JwtAuthenticationFilter} is mocked and short-circuited to a pass-through because
 * {@code @WebMvcTest} auto-detects it as a servlet {@code Filter} bean. {@code /v1/auth/login} is
 * {@code permitAll}, so no {@code @WithMockUser} is needed.
 */
@WebMvcTest(AuthController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class AuthControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private SelfRegistrationService selfRegistrationService;

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
    @DisplayName(
            "login with valid credentials but no roles answers 403 USER_HAS_NO_ROLES with nextAction and correlation id")
    void loginWithNoRolesAnswers403UserHasNoRolesWithNextActionAndCorrelationId() throws Exception {
        when(authenticationService.login(any())).thenThrow(new NoRolesAssignedException("User has no roles assigned"));

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"correct-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("USER_HAS_NO_ROLES"))
                .andExpect(jsonPath("$.message").value("User has no roles assigned"))
                .andExpect(jsonPath("$.nextAction").value(containsString("assign at least one role")));
    }

    /** Clock for {@code GlobalExceptionHandler} and {@code pos-web-common}'s advice, plus method security. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
