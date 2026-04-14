package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.security.common.GatewaySecurityConstants;
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
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for session-lifecycle behaviors that
 * {@link NltiRequestServiceImpl}
 * must implement: session reuse, new-session creation, and expired-session
 * handling.
 *
 * <p>
 * Tests use Mockito to stub {@link NltiSessionRepository} and
 * {@link NltiRequestRepository} so that the service is the sole unit under
 * test. The service is GREEN: all tests exercise the real implementation.
 *
 * Issue: NLTI-001
 */
@ExtendWith(MockitoExtension.class)
class NltiSessionServiceTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String SUBJECT = "nlti-test-user";

    @Mock
    private NltiSessionRepository sessionRepository;

    @Mock
    private NltiRequestRepository requestRepository;

    @Mock
    private Clock clock;

    private NltiRequestServiceImpl service;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUpSecurityContext() {
        meterRegistry = new SimpleMeterRegistry();
        service = new NltiRequestServiceImpl(sessionRepository, requestRepository, clock, meterRegistry);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(SUBJECT, null, List.of());
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, SUBJECT));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Fix ADR-0024: inject deterministic clock; use a base time in the past so "now - 24h" expiry
        // checks work consistently. Tests that need historical data set their own offsets from this base.
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-01-01T12:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── Session reuse: same sessionId + subject → returns existing session ───

    /**
     * AC-5: When a request arrives with a {@code sessionId} that already exists
     * for the same authenticated subject, the service must reuse the existing
     * {@link NltiSession} — no new session record may be created.
     *
     * <p>
     * The session repository stub is primed to return an existing session.
     * The service finds the session and echoes its id in the response.
     */
    @Test
    @DisplayName("AC-5: same sessionId + subject reuses the existing NltiSession")
    void sessionReuse_sameSessionIdAndSubject_returnsExistingSession() {
        // Arrange — Issue NLTI-001: existing session in repository
        NltiSession existing = buildSession(SESSION_ID, SUBJECT);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(existing));

        NltiRequestDTO request = new NltiRequestDTO("check workorder 123", SESSION_ID, null);

        // Act
        NltiResponseV1 response = service.submit(request);

        // Assert: response carries back the same sessionId (no new session created)
        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
    }

    // ─── Session creation: null sessionId → new session record ───────────────

    /**
     * When no {@code sessionId} is supplied, the service must generate a fresh
     * {@link NltiSession} and return its generated id in the response.
     *
     * <p>
     * No repository stubs are primed (empty state). The service
     * calls {@code sessionRepository.save(newSession)} and returns the new id.
     */
    @Test
    @DisplayName("null sessionId → service creates and returns a new NltiSession id")
    void sessionCreation_newSessionId_createsNewRecord() {
        // Arrange — Issue NLTI-001: no client-supplied session
        NltiRequestDTO request = new NltiRequestDTO("list open invoices", null, null);

        // Act
        NltiResponseV1 response = service.submit(request);

        // Assert: a new session UUID is present in the response
        assertThat(response.sessionId()).isNotNull();
    }

    // ─── Session expiry: expired token → new session created ─────────────────

    /**
     * When the supplied {@code sessionId} exists but the session's last-activity
     * timestamp indicates expiry (beyond the configured TTL), the service must
     * discard the stale session and start a fresh one.
     *
     * <p>
     * The repository stub returns a session whose {@code updatedAt} is 25 hours
     * in the past. The service compares the age against the TTL and
     * creates a new session when the old one has expired.
     */
    @Test
    @DisplayName("expired session (updatedAt > TTL ago) → service creates a fresh NltiSession")
    void sessionExpiry_expiredToken_createsNewSession() {
        // Arrange — Issue NLTI-001: stale session with last-activity far in the past
        UUID expiredSessionId = UUID.fromString("00000000-0000-7000-8000-000000000020");
        NltiSession staleSession = buildSession(expiredSessionId, SUBJECT);
        staleSession.setUpdatedAt(OffsetDateTime.parse("2025-12-31T11:00:00Z")); // 25 hours before 2026-01-01T12:00:00Z
        when(sessionRepository.findByIdAndSubjectId(expiredSessionId, SUBJECT)).thenReturn(Optional.of(staleSession));

        NltiRequestDTO request = new NltiRequestDTO("reopen invoice INV-99", expiredSessionId, null);

        // Act
        NltiResponseV1 response = service.submit(request);

        // Assert: a new session was started — returned sessionId must differ from the
        // expired one
        assertThat(response.sessionId()).isNotNull();
        assertThat(response.sessionId()).isNotEqualTo(expiredSessionId);
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
