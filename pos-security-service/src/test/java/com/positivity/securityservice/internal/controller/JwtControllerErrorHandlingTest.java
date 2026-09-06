package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.exception.NoRolesAssignedException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
     * Issue #1715: the response for a token-issuance subject that does not exist must not say
     * which subject or why. The old body echoed {@code "User not found for subject: " + subject},
     * which let any caller holding {@code security:token:issue_internal} enumerate which accounts
     * exist by comparing that message against the answer for a subject that does exist. The
     * message is generic and identical either way; the subject survives only in the correlated
     * WARN log (ADR-0056 §1 — rejected values are never echoed).
     *
     * <p>Issue #1802 moved the status from 400 to 404 {@code USER_NOT_FOUND}: an unresolvable
     * subject is a domain condition, not request shape, and 404 is what every other user
     * reference in this module already answers (ADR-0017 §2, "one condition, one status"). The
     * non-disclosure property survives the move — the body still never names the subject, and
     * the {@code logDetail} channel now rides on {@link UserNotFoundException}.
     */
    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("issueInternalToken answers 404 USER_NOT_FOUND for an unknown subject without naming it or the reason")
    void issueInternalTokenDoesNotDiscloseWhetherTheSubjectExists() throws Exception {
        when(userService.getUserByUsername("ghost.account")).thenReturn(Optional.empty());

        String body = mockMvc.perform(post("/v1/auth/internal/token")
                        .with(csrf())
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"ghost.account\",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Token issuance request is invalid"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("ghost.account")
                .doesNotContain("not found")
                .doesNotContain("User")
                .doesNotContain("subject:");
    }

    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("generateTokenPair answers 404 for an unknown subject on the same terms as issueInternalToken")
    void generateTokenPairDoesNotDiscloseWhetherTheSubjectExists() throws Exception {
        when(userService.getUserByUsername("ghost.account")).thenReturn(Optional.empty());

        String body = mockMvc.perform(post("/v1/auth/token-pair")
                        .with(csrf())
                        .header("X-Correlation-Id", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"ghost.account\",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Token issuance request is invalid"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("ghost.account")
                .doesNotContain("not found")
                .doesNotContain("User")
                .doesNotContain("subject:");
    }

    /**
     * The other half of issue #1715's oracle: the answer for a subject that DOES exist but whose
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
                .andExpect(status().isNotFound())
                // The status, code and message must be byte-identical to the missing-subject path
                // above. Asserting only the status let the previous version through, where this
                // branch answered INVALID_STATE "Resolved user record is missing an id" — a
                // distinct code and a phrase that both tell the caller the subject resolved,
                // which is exactly what the generic message exists to hide.
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Token issuance request is invalid"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("real.account")
                .doesNotContain("INVALID_STATE")
                .doesNotContain("missing an id");
    }

    /**
     * A blank subject is request shape, so it stays 400 (ADR-0017 §1) and must be decided before
     * the user lookup: without the guard an empty subject would fall out of the lookup as the
     * 404 that a subject which does not resolve answers, and one condition would answer two
     * statuses depending on which check ran first (#1802).
     */
    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("issueInternalToken answers 400 VALIDATION_ERROR, not 404, for a blank subject")
    void issueInternalTokenAnswers400ForABlankSubject() throws Exception {
        mockMvc.perform(post("/v1/auth/internal/token")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"  \",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Subject cannot be blank"));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never()).getUserByUsername(anyString());
    }

    @Test
    @WithMockUser(authorities = "security:token:issue_internal")
    @DisplayName("generateTokenPair answers 400 VALIDATION_ERROR, not 404, for a blank subject")
    void generateTokenPairAnswers400ForABlankSubject() throws Exception {
        mockMvc.perform(post("/v1/auth/token-pair")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"\",\"roles\":[\"SHOP_MGR\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Subject cannot be blank"));

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never()).getUserByUsername(anyString());
    }

    /**
     * Issue #1803 (2): {@code JwtServiceImpl#getUserIdFromToken} returns {@code null} by design
     * for a token with neither a {@code uid} nor a legacy {@code userId} claim, and the
     * controller used to call {@code .toString()} on it — a NullPointerException rendered as a
     * generic 500 for what is really a property of the caller's token. It now answers a
     * deliberate 422 {@code TOKEN_USER_ID_MISSING} (ADR-0017 §2 question 3).
     */
    @Test
    @WithMockUser
    @DisplayName("getUserId answers 422 TOKEN_USER_ID_MISSING, not a 500, for a valid token with no uid claim")
    void getUserIdAnswers422WhenTheTokenCarriesNoUserIdClaim() throws Exception {
        when(jwtService.validateToken("legacy-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("legacy-token")).thenReturn(null);

        mockMvc.perform(get("/v1/auth/user-id")
                        .param("token", "legacy-token")
                        .header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("TOKEN_USER_ID_MISSING"))
                .andExpect(jsonPath("$.message").value("Token does not carry a uid or userId claim"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    /**
     * #1808 review: the three token-utility endpoints used to answer a bare
     * {@code ResponseEntity.status(UNAUTHORIZED).build()} when {@code validateToken} refused the
     * query-parameter token — no {@code ApiError} body and no {@code X-Correlation-Id} header,
     * while {@code openapi.yaml} documented an enveloped 401. They now throw
     * {@code InvalidTokenException}, which the advice renders as 401 {@code INVALID_TOKEN} through
     * the same {@code respond} helper as every other handler (ADR-0017 §3/§4).
     */
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/v1/auth/user-id", "/v1/auth/subject", "/v1/auth/roles"})
    @WithMockUser
    @DisplayName("a refused token query parameter answers an enveloped, correlated 401 INVALID_TOKEN")
    void aRefusedTokenAnswersAnEnvelopedCorrelated401(String path) throws Exception {
        when(jwtService.validateToken("refused-token")).thenReturn(false);

        mockMvc.perform(get(path).param("token", "refused-token").header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.message").value("Token invalid or expired"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));

        org.mockito.Mockito.verify(jwtService, org.mockito.Mockito.never()).getRolesFromToken(anyString());
        org.mockito.Mockito.verify(jwtService, org.mockito.Mockito.never()).getUsernameFromToken(anyString());
        org.mockito.Mockito.verify(jwtService, org.mockito.Mockito.never()).getUserIdFromToken(anyString());
    }

    @Test
    @WithMockUser
    @DisplayName("getUserId still answers the uid as a plain string when the claim is present")
    void getUserIdAnswersTheUidWhenPresent() throws Exception {
        UUID uid = UUID.fromString("01990000-0000-7000-8000-000000000042");
        when(jwtService.validateToken("good-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("good-token")).thenReturn(uid);

        mockMvc.perform(get("/v1/auth/user-id").param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(uid.toString()));
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
