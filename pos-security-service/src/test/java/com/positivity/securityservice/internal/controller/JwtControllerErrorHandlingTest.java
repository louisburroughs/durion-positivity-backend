package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.exception.NoRolesAssignedException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.UserService;
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
 * End-to-end proof for issue #1694, exercised through {@link JwtController#refreshAccessToken}
 * (permitAll, so no {@code @WithMockUser} is needed and no request-body deserialization
 * concerns muddy the assertion beyond the {@code refreshToken} field):
 *
 * <ul>
 *   <li>{@link SecurityValidationException} keeps this module's genuine-client-error contract
 *       (400 {@code INVALID_REQUEST}, message echoed) — the blanket
 *       {@code @ExceptionHandler(IllegalArgumentException.class)} that used to catch this is
 *       gone.
 *   <li>{@link NoRolesAssignedException} answers the 403 {@code USER_HAS_NO_ROLES} mapping
 *       (ADR-0017 §2 question 1, decided in #1725) added alongside this sweep.
 *   <li>A bare {@code IllegalArgumentException} — what Hibernate/JPA throw for an invalid query,
 *       what {@code UUID.fromString} throws on malformed stored data — is no longer caught by
 *       this module's {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler}:
 *       it falls through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler},
 *       which answers a generic, correlated 500 that never echoes the exception's own text.
 * </ul>
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration}
 * (it is not on the curated slice-test allowlist), so {@link WebCommonErrorAutoConfiguration} is
 * imported explicitly here to exercise the real fallback chain rather than asserting a weaker
 * substitute. {@link JwtAuthenticationFilter} is mocked and short-circuited to a pass-through
 * because {@code @WebMvcTest} auto-detects it as a servlet {@code Filter} bean (this module's
 * existing controller-slice pattern, see e.g. {@code AuditControllerTest}); this is a security
 * service, and this endpoint deliberately does not use {@code @WithMockUser}.
 */
@WebMvcTest(JwtController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class JwtControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserService userService;

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
    @DisplayName("a SecurityValidationException failure still answers 400 INVALID_REQUEST with its own message")
    void aSecurityValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(jwtService.refreshAccessToken(anyString()))
                .thenThrow(new SecurityValidationException("Invalid refresh token"));

        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    @DisplayName("a NoRolesAssignedException answers the 403 USER_HAS_NO_ROLES mapping")
    void aNoRolesAssignedFailureAnswers403WithItsOwnMessageAndCode() throws Exception {
        when(jwtService.refreshAccessToken(anyString()))
                .thenThrow(new NoRolesAssignedException("User has no roles assigned"));

        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"a-valid-looking-token\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_HAS_NO_ROLES"))
                .andExpect(jsonPath("$.message").value("User has no roles assigned"))
                .andExpect(jsonPath("$.nextAction").value(containsString("assign at least one role")));
    }

    /**
     * The regression this test guards against (issue #1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure — the kind Hibernate/JPA or {@code UUID.fromString} throw on internal defects — so
     * it must land on the generic, correlated 500 fallback, and the correlation id must be
     * present in the body (ADR-0017 §4).
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 INTERNAL_ERROR without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'jti' of "
                + "'com.positivity.securityservice.internal.entity.JwtToken'";
        when(jwtService.refreshAccessToken(anyString())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"a-valid-looking-token\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("jti");
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
