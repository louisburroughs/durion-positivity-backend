package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
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
 * Observability metrics RED tests for {@link NltiRequestServiceImpl} (AC1–AC3 of NLTI-009).
 *
 * <p>Verifies that {@code submit()} increments {@code nlt.request.count}, records a
 * sample in {@code nlt.request.latency_ms}, and increments {@code nlt.error.count}
 * when a dependency throws.
 *
 * <p>These tests verify the GREEN implementation of NLTI-009 observability:
 * {@link NltiRequestServiceImpl} accepts a {@link io.micrometer.core.instrument.MeterRegistry}
 * in its constructor and calls the wired counters/timer at the correct points in
 * {@code submit()}.
 *
 * Issue: NLTI-009
 */
@ExtendWith(MockitoExtension.class)
class NltiMetricsTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000901");
    private static final UUID CORR_ID    = UUID.fromString("00000000-0000-7000-8000-000000000902");
    private static final UUID CORR_ID_2  = UUID.fromString("00000000-0000-7000-8000-000000000903");
    private static final String SUBJECT  = "nlti-metrics-user";

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-12T10:00:00Z");

    @Mock
    private NltiSessionRepository sessionRepository;

    @Mock
    private NltiRequestRepository requestRepository;

    @Mock
    private Clock clock;

    // Real in-process registry — counter/timer counts are queryable
    private SimpleMeterRegistry registry;
    private Counter requestCount;
    private Timer   requestLatencyMs;
    private Counter errorCount;

    // Service constructed directly — @InjectMocks not used because the registry
    // is not yet a constructor parameter; GREEN will add it.
    private NltiRequestServiceImpl service;

    private NltiRequestDTO request;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Pre-register metrics exactly as NltiObservabilityMetricsConfig does at runtime
        requestCount     = Counter.builder("nlt.request.count").register(registry);
        requestLatencyMs = Timer.builder("nlt.request.latency_ms").register(registry);
        errorCount       = Counter.builder("nlt.error.count").register(registry);

        service = new NltiRequestServiceImpl(sessionRepository, requestRepository, clock, registry);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(SUBJECT, null, List.of());
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, SUBJECT));
        SecurityContextHolder.getContext().setAuthentication(auth);

        lenient().when(clock.instant()).thenReturn(FIXED_INSTANT);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        NltiSession session = new NltiSession();
        session.setId(SESSION_ID);
        session.setSubjectId(SUBJECT);
        session.setCreatedAt(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        session.setUpdatedAt(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        lenient().when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT))
                .thenReturn(Optional.of(session));

        request = new NltiRequestDTO("list open workorders", SESSION_ID, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── AC1: nlt.request.count ────────────────────────────────────────────

    /**
     * A single {@code submit()} call must increment {@code nlt.request.count} to 1.
     */
    @Test
    @DisplayName("submit() increments nlt.request.count by 1")
    void submit_incrementsRequestCount() {
        service.submit(request, CORR_ID);

        assertThat(requestCount.count())
                .as("nlt.request.count should be 1.0 after one submit()")
                .isEqualTo(1.0);
    }

    /**
     * Two consecutive {@code submit()} calls must increment {@code nlt.request.count}
     * to exactly 2.
     */
    @Test
    @DisplayName("submit() called twice increments nlt.request.count to 2")
    void submit_incrementsRequestCount_after_two_calls() {
        service.submit(request, CORR_ID);
        service.submit(request, CORR_ID_2);

        assertThat(requestCount.count())
                .as("nlt.request.count should be 2.0 after two submit() calls")
                .isEqualTo(2.0);
    }

    // ─── AC2: nlt.request.latency_ms ──────────────────────────────────────

    /**
     * {@code submit()} must record exactly one sample in the
     * {@code nlt.request.latency_ms} timer.
     */
    @Test
    @DisplayName("submit() records one sample in nlt.request.latency_ms timer")
    void submit_recordsRequestLatency() {
        service.submit(request, CORR_ID);

        assertThat(requestLatencyMs.count())
                .as("nlt.request.latency_ms should have 1 recorded sample after submit()")
                .isEqualTo(1L);
    }

    // ─── AC3: nlt.error.count ─────────────────────────────────────────────

    /**
     * When a dependency throws during {@code submit()}, {@code nlt.error.count}
     * must be incremented before the exception is re-thrown.
     */
    @Test
    @DisplayName("submit() increments nlt.error.count when a dependency throws")
    void submit_onException_incrementsErrorCount() {
        when(requestRepository.save(any()))
                .thenThrow(new RuntimeException("simulated repository failure"));

        assertThatThrownBy(() -> service.submit(request, CORR_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(errorCount.count())
                .as("nlt.error.count should be 1.0 after exception in submit()")
                .isEqualTo(1.0);
    }
}
