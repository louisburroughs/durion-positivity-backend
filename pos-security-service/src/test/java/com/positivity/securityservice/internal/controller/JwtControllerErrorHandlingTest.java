package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
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
import java.util.Optional;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link JwtController#refreshAccessToken}
 * (permitAll, so no {@code @WithMockUser} is needed and no request-body deserialization
 * concerns muddy the assertion beyond the {@code refreshToken} field):
 *
 * <ul>
 *   <li>{@link SecurityValidationException} keeps this module's genuine-client-error contract
 *       (400 {@code VALIDATION_ERROR}, message echoed) — the blanket
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
 *
 * <p>This class also proves, at the HTTP boundary, that an inbound {@code X-Correlation-Id} is
 * echoed in both the response header and the {@code ApiError} body for the pre-existing handlers
 * exercised here — {@link InvalidRefreshTokenException}, {@link SecurityValidationException}, and
 * {@link NoRolesAssignedException} — now that every handler routes through the single {@code
 * respond} helper in {@code GlobalExceptionHandler} (ADR-0017 §4, issue #1729).
 */
@WebMvcTest(JwtController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class JwtControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID = "test-correlation-id-1729";

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
    @DisplayName("a SecurityValidationException failure answers 400 VALIDATION_ERROR with its own message")
    void aSecurityValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(jwtService.refreshAccessToken(anyString()))
                .thenThrow(new SecurityValidationException("Invalid refresh token"));

        mockMvc.perform(post("/v1/auth/refresh")
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid refresh token"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    @DisplayName("a NoRolesAssignedException answers the 403 USER_HAS_NO_ROLES mapping")
    void aNoRolesAssignedFailureAnswers403WithItsOwnMessageAndCode() throws Exception {
        when(jwtService.refreshAccessToken(anyString()))
                .thenThrow(new NoRolesAssignedException("User has no roles assigned"));

        mockMvc.perform(post("/v1/auth/refresh")
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"a-valid-looking-token\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("USER_HAS_NO_ROLES"))
                .andExpect(jsonPath("$.message").value("User has no roles assigned"))
                .andExpect(jsonPath("$.nextAction").value(containsString("assign at least one role")))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    @DisplayName("an InvalidRefreshTokenException answers 401 INVALID_REFRESH_TOKEN with the echoed correlation id")
    void anInvalidRefreshTokenFailureAnswers401WithEchoedCorrelationId() throws Exception {
        when(jwtService.refreshAccessToken(anyString()))
                .thenThrow(new InvalidRefreshTokenException("Refresh token references a user that no longer exists"));

        mockMvc.perform(post("/v1/auth/refresh")
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"a-valid-looking-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.message").value("Refresh token references a user that no longer exists"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
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

    /**
     * Issue #1715: the 400 for a token-issuance subject that does not exist must not say so. The
     * old body echoed {@code "User not found for subject: " + subject}, which let any caller
     * holding {@code security:token:issue_internal} enumerate which accounts exist by comparing
     * that message against the answer for a subject that does exist. The message is now generic
     * and identical either way; the subject survives only in the correlated WARN log
     * (ADR-0056 §1 — rejected values are never echoed).
     */
    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("issueInternalToken hides whether the subject exists: the 400 body never names it or the reason")
    void issueInternalTokenDoesNotDiscloseWhetherTheSubjectExists() throws Exception {
        when(userService.getUserByUsername("ghost.account")).thenReturn(Optional.empty());

        String body = mockMvc.perform(post("/v1/auth/internal/token")
                        .with(csrf())
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"ghost.account\",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Token issuance request is invalid"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("ghost.account")
                .doesNotContain("not found")
                .doesNotContain("User");
    }

    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("generateTokenPair hides whether the subject exists on the same terms as issueInternalToken")
    void generateTokenPairDoesNotDiscloseWhetherTheSubjectExists() throws Exception {
        when(userService.getUserByUsername("ghost.account")).thenReturn(Optional.empty());

        String body = mockMvc.perform(post("/v1/auth/token-pair")
                        .with(csrf())
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"ghost.account\",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Token issuance request is invalid"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("ghost.account").doesNotContain("not found");
    }

    /**
     * The other half of issue #1715's oracle: the message for a subject that DOES exist but whose
     * stored record has no id must be indistinguishable in the same way — it previously echoed
     * "User exists but id is missing for subject: X".
     */
    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("issueInternalToken does not name the subject when the resolved user record has no id")
    void issueInternalTokenDoesNotNameTheSubjectWhenTheUserRecordHasNoId() throws Exception {
        UserAuthContext idlessUser =
                UserAuthContext.builder().username("real.account").build();
        when(userService.getUserByUsername("real.account")).thenReturn(Optional.of(idlessUser));

        String body = mockMvc.perform(post("/v1/auth/internal/token")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"real.account\",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isBadRequest())
                // The code and message must be byte-identical to the missing-subject path above.
                // Asserting only the status let the previous version through, where this branch
                // answered INVALID_STATE "Resolved user record is missing an id" — a distinct code
                // and a phrase that both tell the caller the subject resolved, which is exactly
                // what the generic message exists to hide.
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Token issuance request is invalid"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("real.account")
                .doesNotContain("INVALID_STATE")
                .doesNotContain("missing an id");
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
