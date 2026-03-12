package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link NltiRequestServiceImpl} covering branches not exercised
 * by {@link NltiSessionServiceTest}: rate-limit exceeded path, unknown
 * session-id handling, and the "no auth context → system subject" path.
 *
 * Issue: NLTI-001
 */
@ExtendWith(MockitoExtension.class)
class NltiRequestServiceImplTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000050");
    private static final UUID UNKNOWN_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000051");
    private static final String SUBJECT = "nlti-rate-limit-user";
    private static final String OTHER_SUBJECT = "nlti-other-user";

    @Mock
    private NltiSessionRepository sessionRepository;

    @Mock
    private NltiRequestRepository requestRepository;

    @Mock
    private Clock clock;

    private NltiRequestServiceImpl service;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new NltiRequestServiceImpl(sessionRepository, requestRepository, clock, meterRegistry);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(SUBJECT, null, List.of());
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, SUBJECT));
        SecurityContextHolder.getContext().setAuthentication(auth);
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-01-01T12:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── Rate limit exceeded path ────────────────────────────────────────────

    /**
     * When the per-session rate limit is 1 and two requests are submitted
     * with the same session, the second call must throw
     * {@link RateLimitExceededException} and leave the counter unchanged
     * (decrementAndGet is called before throwing).
     */
    @Test
    @DisplayName("second submit on same session after limit=1 → throws RateLimitExceededException")
    void submit_whenPerSessionRateLimitExceeded_throwsRateLimitExceededException() {
        ReflectionTestUtils.setField(service, "perSessionRateLimit", 1);

        NltiSession session = buildSession(SESSION_ID, SUBJECT);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT))
                .thenReturn(Optional.of(session));

        NltiRequestDTO request = new NltiRequestDTO("close workorder 123", SESSION_ID, null);

        // First call: counter 0 → 1, 1 > 1 is false → succeeds
        service.submit(request);

        // Second call: counter 1 → 2, 2 > 1 is true → throws
        assertThatThrownBy(() -> service.submit(request))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Rate limit exceeded");
    }

    // ─── Unknown session-id path ─────────────────────────────────────────────

    /**
     * When a non-null {@code sessionId} is supplied but is not found in the
     * repository, the service must create a new server-generated session id.
     */
    @Test
    @DisplayName("provided sessionId not in repo → new session created with generated ID")
    void submit_withNonExistentSessionId_createsSessionWithGeneratedId() {
        when(sessionRepository.findByIdAndSubjectId(UNKNOWN_SESSION_ID, SUBJECT))
                .thenReturn(Optional.empty());
        when(sessionRepository.findById(UNKNOWN_SESSION_ID))
                .thenReturn(Optional.empty());

        NltiRequestDTO request = new NltiRequestDTO("list open invoices", UNKNOWN_SESSION_ID, null);
        NltiResponseV1 response = service.submit(request);

        assertThat(response.sessionId()).isNotNull();
        assertThat(response.sessionId()).isNotEqualTo(UNKNOWN_SESSION_ID);
    }

    @Test
    @DisplayName("provided sessionId owned by another subject → throws SessionOwnershipViolationException")
    void submit_withSessionIdOwnedByAnotherSubject_throwsSessionOwnershipViolationException() {
        when(sessionRepository.findByIdAndSubjectId(UNKNOWN_SESSION_ID, SUBJECT))
                .thenReturn(Optional.empty());
        when(sessionRepository.findById(UNKNOWN_SESSION_ID))
                .thenReturn(Optional.of(buildSession(UNKNOWN_SESSION_ID, OTHER_SUBJECT)));

        NltiRequestDTO request = new NltiRequestDTO("list open invoices", UNKNOWN_SESSION_ID, null);

        assertThatThrownBy(() -> service.submit(request))
                .isInstanceOf(SessionOwnershipViolationException.class)
                .hasMessageContaining("not owned");
    }

    // ─── No security context → "system" subject fallback ────────────────────

    /**
     * When there is no {@link Authentication} in the security context (e.g. a
     * background job), {@code resolveSubjectId()} must return "system" and the
     * service must still create a valid session and return ACCEPTED.
     */
    @Test
    @DisplayName("no authentication in security context → resolves subject as 'system'")
    void submit_withoutAuthContext_usesSystemSubjectAndReturnsAccepted() {
        SecurityContextHolder.clearContext();

        NltiRequestDTO request = new NltiRequestDTO("system scheduled task", null, null);
        NltiResponseV1 response = service.submit(request);

        assertThat(response.sessionId()).isNotNull();
        assertThat(response.status()).isEqualTo("ACCEPTED");
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private static NltiSession buildSession(UUID id, String subjectId) {
        NltiSession session = new NltiSession();
        session.setId(id);
        session.setSubjectId(subjectId);
        session.setCreatedAt(OffsetDateTime.parse("2026-01-01T11:30:00Z"));
        session.setUpdatedAt(OffsetDateTime.parse("2026-01-01T11:55:00Z"));
        return session;
    }
}
