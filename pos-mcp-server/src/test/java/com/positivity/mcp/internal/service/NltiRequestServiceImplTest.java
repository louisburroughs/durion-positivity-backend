package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryPublisher;
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
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000052");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000053");
    private static final String SUBJECT = "nlti-rate-limit-user";
    private static final String OTHER_SUBJECT = "nlti-other-user";

    @Mock
    private IntentParserService intentParserService;

    @Mock
    private NltiWritePlanService writePlanService;

    @Mock
    private NltiSessionRepository sessionRepository;

    @Mock
    private NltiRequestRepository requestRepository;

    @Mock
    private Clock clock;

    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    private NltiRequestServiceImpl service;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new NltiRequestServiceImpl(
                sessionRepository,
                requestRepository,
                intentParserService,
                writePlanService,
                telemetry.publisher(),
                clock,
                meterRegistry);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(SUBJECT, null, List.of());
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, SUBJECT));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Gate 6 (#1193): default to a QUERY classification so submit keeps the ACCEPTED path.
        org.mockito.Mockito.lenient()
                .when(intentParserService.parse(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IntentV1(
                        UUID.fromString("00000000-0000-7000-8000-00000000fee1"),
                        "QUERY",
                        "READY",
                        "LOW",
                        java.util.List.of(),
                        java.util.List.of()));
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
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));

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
        when(sessionRepository.findById(UNKNOWN_SESSION_ID)).thenReturn(Optional.empty());

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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName(
            "Gate 6 (#1193): an ACTION-classified request is routed to the write-plan preview, not ACCEPTED")
    void submit_actionIntent_delegatesToWritePlanPreview() {
        org.mockito.Mockito.when(intentParserService.parse(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IntentV1(
                        UUID.fromString("00000000-0000-7000-8000-00000000fee2"),
                        "ACTION",
                        "READY",
                        "MEDIUM",
                        java.util.List.of(),
                        java.util.List.of()));
        NltiResponseV1 preview = new NltiResponseV1(
                UUID.fromString("00000000-0000-7000-8000-00000000fee3"),
                UUID.fromString("00000000-0000-7000-8000-00000000fee4"),
                UUID.fromString("00000000-0000-7000-8000-00000000fee5"),
                "PENDING_CONFIRMATION",
                null,
                null);
        org.mockito.Mockito.when(writePlanService.previewAction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(preview);

        NltiResponseV1 response = service.submit(new NltiRequestDTO("create a purchase order", null, null));

        org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        org.mockito.Mockito.verify(writePlanService)
                .previewAction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    // ─── #1397: request telemetry on the NLTI submit path ────────────────────

    /**
     * The defect #1397 reports: the write gate ran but nothing on the telemetry stream said so, so
     * the harness's {@code wg-telemetry} check SKIPped on every live run. An ACTION submit must now
     * produce a record carrying the caller's correlation id and {@code write.isWrite=true}.
     */
    @Test
    @DisplayName("ACTION submit → telemetry record carries the correlation id and write.isWrite=true")
    void submit_actionIntent_emitsWriteTelemetryForTheCorrelationId() {
        stubActionIntent("HIGH");
        when(writePlanService.previewAction(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new NltiResponseV1(REQUEST_ID, CORRELATION_ID, SESSION_ID, "PENDING_CONFIRMATION", null, null));

        service.submit(new NltiRequestDTO("create a purchase order", null, null), CORRELATION_ID);

        NltiRequestTelemetry event = telemetry.only();
        assertThat(event.eventType()).isEqualTo(NltiRequestTelemetry.EVENT_TYPE);
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(event.write()).isNotNull();
        assertThat(event.write().isWrite()).isTrue();
        assertThat(event.routing().intentType()).isEqualTo("ACTION");
        assertThat(event.routing().riskLevel()).isEqualTo("HIGH");
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
        // The submit leg has no confirmation outcome yet — that is the confirm/cancel call's job.
        assertThat(event.write().confirmationOutcome()).isNull();
        // Both ids exist on this path, unlike the chat path.
        assertThat(event.sessionId()).isNotNull();
        assertThat(event.requestId()).isNotNull();
    }

    @Test
    @DisplayName("QUERY submit → telemetry record omits the write block")
    void submit_queryIntent_emitsTelemetryWithoutWriteBlock() {
        service.submit(new NltiRequestDTO("list open invoices", null, null), CORRELATION_ID);

        NltiRequestTelemetry event = telemetry.only();
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(event.routing().intentType()).isEqualTo("QUERY");
        // Omitted, not isWrite=false: a read never entered the write gate.
        assertThat(event.write()).isNull();
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
    }

    /**
     * A rejected request is exactly the case an operator needs on the stream, so emission happens
     * in a finally block rather than on the success path only.
     */
    @Test
    @DisplayName("rate-limited submit → telemetry record carries ERROR and the exception discriminator")
    void submit_whenRateLimited_emitsErrorTelemetry() {
        ReflectionTestUtils.setField(service, "perSessionRateLimit", 1);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT))
                .thenReturn(Optional.of(buildSession(SESSION_ID, SUBJECT)));
        NltiRequestDTO request = new NltiRequestDTO("close workorder 123", SESSION_ID, null);

        service.submit(request);
        assertThatThrownBy(() -> service.submit(request)).isInstanceOf(RateLimitExceededException.class);

        assertThat(telemetry.events()).hasSize(2);
        NltiRequestTelemetry rejected = telemetry.events().get(1);
        assertThat(rejected.outcome().status()).isEqualTo("ERROR");
        assertThat(rejected.outcome().errorCode()).isEqualTo("RateLimitExceededException");
        // The limit is enforced after the session resolves but before the request row is written.
        assertThat(rejected.sessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(rejected.requestId()).isNull();
    }

    /** A telemetry emitter that blows up must never turn an accepted request into a failed one. */
    @Test
    @DisplayName("emitter throwing → submit still returns ACCEPTED")
    void submit_whenTelemetryEmitterThrows_doesNotFailTheRequest() {
        NltiRequestServiceImpl serviceWithFailingEmitter = new NltiRequestServiceImpl(
                sessionRepository,
                requestRepository,
                intentParserService,
                writePlanService,
                new NltiRequestTelemetryPublisher(
                        event -> {
                            throw new IllegalStateException("emitter down");
                        },
                        new McpRoleResolverImpl(),
                        Clock.fixed(Instant.parse("2026-08-19T10:15:30Z"), ZoneOffset.UTC)),
                clock,
                meterRegistry);

        NltiResponseV1 response =
                serviceWithFailingEmitter.submit(new NltiRequestDTO("list open invoices", null, null));

        assertThat(response.status()).isEqualTo("ACCEPTED");
    }

    private void stubActionIntent(String riskLevel) {
        when(intentParserService.parse(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IntentV1(
                        UUID.fromString("00000000-0000-7000-8000-00000000fee2"),
                        "ACTION",
                        "READY",
                        riskLevel,
                        List.of(),
                        List.of()));
    }
}
