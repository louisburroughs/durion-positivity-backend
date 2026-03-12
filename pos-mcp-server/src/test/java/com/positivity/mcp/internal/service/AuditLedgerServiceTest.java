package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.mcp.internal.dto.AuditQuery;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.repository.NltiAuditEventRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link AuditLedgerServiceImpl}: append, query, idempotency,
 * actor resolution from security context, and metric emission on write failure.
 *
 * <p>Tests use Mockito to stub {@link NltiAuditEventRepository} and
 * {@link MeterRegistry} so that the service is the sole unit under test.
 *
 * Issue: NLTI-007
 */
@ExtendWith(MockitoExtension.class)
class AuditLedgerServiceTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID CORRELATION_ID   = UUID.fromString("00000000-0000-7000-8000-000000000200");
    private static final UUID SESSION_ID       = UUID.fromString("00000000-0000-7000-8000-000000000201");
    private static final UUID REQUEST_ID       = UUID.fromString("00000000-0000-7000-8000-000000000202");
    private static final UUID EVENT_ID         = UUID.fromString("00000000-0000-7000-8000-000000000203");
    private static final UUID EVENT_ID_QUERY_1 = UUID.fromString("00000000-0000-7000-8000-000000000210");
    private static final UUID EVENT_ID_QUERY_2 = UUID.fromString("00000000-0000-7000-8000-000000000211");
    private static final UUID EVENT_ID_DATE_1  = UUID.fromString("00000000-0000-7000-8000-000000000220");
    private static final UUID EVENT_ID_ETYPE_1 = UUID.fromString("00000000-0000-7000-8000-000000000230");

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

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── append: happy path ──────────────────────────────────────────────────

    /**
     * append() with a valid event must delegate to repository.save().
     *
     * RED: AuditLedgerServiceImpl throws UnsupportedOperationException —
     * verify(auditEventRepository).save() is never reached.
     */
    @Test
    @DisplayName("append with valid event delegates to repository.save()")
    void append_withValidEvent_delegatesToRepository() {
        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.REQUEST);

        // Issue NLTI-007: impl must persist the event via repository
        service.append(event);

        verify(auditEventRepository).save(event);
    }

    // ─── append: actor resolution from security context ──────────────────────

    /**
     * When a security context with a named principal is present, append() must
     * populate actorSubjectId from the authenticated user's name before saving.
     *
     * RED: impl throws UnsupportedOperationException before reaching repository.
     */
    @Test
    @DisplayName("append sets actorSubjectId from SecurityContext principal name")
    void append_setsActorSubjectIdFromSecurityContext() {
        // Issue NLTI-007 / ADR-0018: actor fields from security context
        // SecurityContextHelper requires details map with DETAIL_USERNAME key (gateway pattern)
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "audit-user", null, List.of());
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "audit-user"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.REQUEST);

        service.append(event);

        var captor = forClass(NltiAuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActorSubjectId()).isEqualTo("audit-user");
    }

    // ─── append: metric on repository failure ────────────────────────────────

    /**
     * When repository.save() throws a RuntimeException, append() must:
     * (1) NOT propagate the exception (fire-and-continue audit semantics), and
     * (2) emit the {@code nlt.audit.write_failures} Micrometer counter.
     *
     * RED: impl throws UnsupportedOperationException before any repository or
     * metric interaction — assertThatCode detects the unexpected UOE.
     */
    @Test
    @DisplayName("append emits nlt.audit.write_failures counter when repository throws")
    void append_whenRepositoryThrows_emitsWriteFailureMetric() {
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter("nlt.audit.write_failures")).thenReturn(counter);
        when(auditEventRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.REQUEST);

        // Issue NLTI-007: service must catch exception, emit metric, and not rethrow
        assertThatCode(() -> service.append(event)).doesNotThrowAnyException();

        verify(counter).increment();
    }

    // ─── append: idempotency ─────────────────────────────────────────────────

    /**
     * Calling append() twice with the same event id must result in exactly one
     * repository.save() invocation (idempotent write from the writer's perspective).
     *
     * RED: first call throws UnsupportedOperationException.
     */
    @Test
    @DisplayName("append called twice with same eventId saves only once (idempotency)")
    void append_idempotent_whenSameEventIdAppendedTwice_savesOnce() {
        NltiAuditEvent event = buildEvent(EVENT_ID, NltiAuditEventType.INTENT);

        // Issue NLTI-007: duplicate event id must not cause double write
        service.append(event);
        service.append(event);

        verify(auditEventRepository, times(1)).save(any());
    }

    // ─── query: by correlationId ─────────────────────────────────────────────

    /**
     * query() with a non-null correlationId must delegate to the
     * findByCorrelationIdOrderByTimestampAsc repository method and return
     * events mapped to {@link AuditEventResponse} in ascending timestamp order.
     *
     * RED: impl throws UnsupportedOperationException before delegating.
     */
    @Test
    @DisplayName("query by correlationId returns paged results in timestamp order")
    void query_byCorrelationId_returnsPagedResultsInTimestampOrder() {
        NltiAuditEvent e1 = buildEvent(EVENT_ID_QUERY_1, NltiAuditEventType.REQUEST);
        NltiAuditEvent e2 = buildEvent(EVENT_ID_QUERY_2, NltiAuditEventType.INTENT);
        e2.setTimestamp(OffsetDateTime.ofInstant(FIXED_INSTANT.plusSeconds(5), ZoneOffset.UTC));

        Pageable pageable = PageRequest.of(0, 20);
        when(auditEventRepository.findByCorrelationIdOrderByTimestampAsc(CORRELATION_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(e1, e2)));

        // Issue NLTI-007: service must delegate to repository and map entities to DTOs
        Page<AuditEventResponse> result = service.query(
                new AuditQuery(CORRELATION_ID, null, null, null), pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).eventType()).isEqualTo("REQUEST");
        assertThat(result.getContent().get(1).eventType()).isEqualTo("INTENT");
    }

    // ─── query: by date range ────────────────────────────────────────────────

    /**
     * query() with a non-null from/to date range and null correlationId must
     * delegate to the findByTimestampBetweenOrderByTimestampAsc repository method.
     *
     * RED: impl throws UnsupportedOperationException before delegating.
     */
    @Test
    @DisplayName("query by date range returns filtered results")
    void query_byDateRange_returnsFilteredResults() {
        NltiAuditEvent e1 = buildEvent(EVENT_ID_DATE_1, NltiAuditEventType.EXECUTION_COMPLETE);

        Pageable pageable = PageRequest.of(0, 20);
        when(auditEventRepository.findByTimestampBetweenOrderByTimestampAsc(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(e1)));

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-01-01T23:59:59Z");

        // Issue NLTI-007: service must delegate to date-range repo method and map to DTOs
        Page<AuditEventResponse> result = service.query(
                new AuditQuery(null, from, to, null), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).eventType()).isEqualTo("EXECUTION_COMPLETE");
    }

    // ─── query: by eventType ──────────────────────────────────────────────────

    /**
     * query() with a non-null eventType and null correlationId / date range must
     * delegate to the findByEventTypeOrderByTimestampAsc repository method and
     * return events mapped to {@link AuditEventResponse}.
     *
     * Issue: NLTI-007 (AC-2, F1 coverage)
     */
    @Test
    @DisplayName("query by eventType returns filtered results")
    void query_byEventType_returnsFilteredResults() {
        NltiAuditEvent e1 = buildEvent(EVENT_ID_ETYPE_1, NltiAuditEventType.REQUEST);

        Pageable pageable = PageRequest.of(0, 20);
        when(auditEventRepository.findByEventTypeOrderByTimestampAsc(NltiAuditEventType.REQUEST, pageable))
                .thenReturn(new PageImpl<>(List.of(e1)));

        // Issue NLTI-007: service must delegate to event-type repo method and map to DTOs
        Page<AuditEventResponse> result = service.query(
                new AuditQuery(null, null, null, "REQUEST"), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).eventType()).isEqualTo("REQUEST");
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid {@link NltiAuditEvent} with the given id and type.
     *
     * @param id   event UUID (must be a hardcoded constant per ADR-0013)
     * @param type audit event type
     * @return fully initialised entity with timestamps set
     */
    private static NltiAuditEvent buildEvent(UUID id, NltiAuditEventType type) {
        NltiAuditEvent event = new NltiAuditEvent();
        event.setId(id);
        event.setCorrelationId(CORRELATION_ID);
        event.setSessionId(SESSION_ID);
        event.setRequestId(REQUEST_ID);
        event.setEventType(type);
        event.setTimestamp(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        event.setCreatedAt(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        return event;
    }
}
