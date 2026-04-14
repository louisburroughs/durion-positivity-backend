package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.repository.NltiAuditEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Dedicated tests for write-failure fire-and-continue semantics and the
 * {@code nlt.audit.write_failures} metric in {@link AuditLedgerServiceImpl}.
 *
 * <p>Verifies that a repository failure during audit append:
 * <ul>
 * <li>Does NOT propagate to the caller (audit writes must never block
 * execution).</li>
 * <li>Increments the {@code nlt.audit.write_failures} Micrometer counter.</li>
 * </ul>
 *
 * Issue: NLTI-007
 */
@ExtendWith(MockitoExtension.class)
class AuditWriteFailureTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000200");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000203");

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private NltiAuditEventRepository auditEventRepository;

    @Mock
    private Clock clock;

    @Mock
    private MeterRegistry meterRegistry;

    @InjectMocks
    private AuditLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(FIXED_INSTANT);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ─── fire-and-continue semantics ─────────────────────────────────────────

    /**
     * When repository.save() throws a RuntimeException, append() must swallow the
     * exception and return normally (fire-and-continue audit write semantics).
     *
     * <p>Audit writes must never propagate failures to the caller — a write failure
     * must be silently absorbed and logged/metered, not re-thrown.
     *
     * RED: UnsupportedOperationException propagates from stub because impl is not
     * yet implemented — assertThatCode detects unexpected exception.
     */
    @Test
    @DisplayName("append swallows repository exceptions (fire-and-continue audit semantics)")
    void append_whenRepositoryThrows_doesNotPropagateException() {
        when(auditEventRepository.save(any())).thenThrow(new RuntimeException("Connection refused"));

        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.REQUEST);

        // Issue NLTI-007: audit write failure must not block execution path
        assertThatCode(() -> service.append(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("append with EXECUTION_STEP event re-throws when repository fails (gates destructive execution)")
    void append_withDestructiveEventType_whenRepositoryThrows_propagatesException() {
        when(auditEventRepository.save(any())).thenThrow(new RuntimeException("DB timeout"));
        Counter mockCounter = mock(Counter.class);
        when(meterRegistry.counter("nlt.audit.write_failures")).thenReturn(mockCounter);

        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.EXECUTION_STEP);

        // Issue NLTI-007 AC-4: write failure must block destructive execution paths
        assertThatThrownBy(() -> service.append(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB timeout");
        verify(mockCounter).increment();
    }

    static Stream<NltiAuditEventType> destructiveEventTypes() {
        return Stream.of(
                NltiAuditEventType.EXECUTION_STEP,
                NltiAuditEventType.EXECUTION_COMPLETE,
                NltiAuditEventType.EXECUTION_FAILED);
    }

    @ParameterizedTest(name = "{0} re-throws on repository failure")
    @MethodSource("destructiveEventTypes")
    @DisplayName("append with any destructive event type re-throws and increments counter")
    void append_withAnyDestructiveType_whenRepositoryThrows_propagatesExceptionAndEmitsCounter(
            NltiAuditEventType destructiveType) {
        when(auditEventRepository.save(any())).thenThrow(new RuntimeException("DB error"));
        Counter mockCounter = mock(Counter.class);
        when(meterRegistry.counter("nlt.audit.write_failures")).thenReturn(mockCounter);

        NltiAuditEvent event = buildEvent(EVENT_ID, destructiveType);

        // Issue NLTI-007 AC-4: all three destructive types must block execution on write failure
        assertThatThrownBy(() -> service.append(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB error");
        verify(mockCounter).increment();
    }

    // ─── metric on write failure ─────────────────────────────────────────────

    /**
     * When repository.save() throws, append() must increment the
     * {@code nlt.audit.write_failures} Micrometer counter exactly once.
     *
     * RED: UnsupportedOperationException propagates from stub — assertThatCode
     * detects unexpected exception and verify(mockCounter) is never reached.
     */
    @Test
    @DisplayName("append increments nlt.audit.write_failures counter on repository failure")
    void append_whenRepositoryThrows_incrementsWriteFailureCounter() {
        Counter mockCounter = mock(Counter.class);
        when(meterRegistry.counter("nlt.audit.write_failures")).thenReturn(mockCounter);
        when(auditEventRepository.save(any())).thenThrow(new RuntimeException("Timeout"));

        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.REQUEST);

        // Issue NLTI-007: metric emission is required on audit write failure
        assertThatCode(() -> service.append(event)).doesNotThrowAnyException();

        verify(mockCounter).increment();
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid {@link NltiAuditEvent} for write-failure path tests.
     *
     * @param id   event UUID (hardcoded constant per ADR-0013)
     * @param type audit event type
     * @return fully initialised entity with timestamps set
     */
    private static NltiAuditEvent buildEvent(UUID id, NltiAuditEventType type) {
        NltiAuditEvent event = new NltiAuditEvent();
        event.setId(id);
        event.setCorrelationId(CORRELATION_ID);
        event.setEventType(type);
        event.setTimestamp(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        event.setCreatedAt(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        return event;
    }
}
