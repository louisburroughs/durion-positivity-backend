package com.positivity.mcp.internal.service;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.dto.ClarificationResponseDTO;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.internal.entity.NltiIntent;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.enums.NltiIntentStatus;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.repository.NltiIntentRepository;
import com.positivity.mcp.service.AuditLedgerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration-style unit tests verifying that {@link IntentParserServiceImpl}
 * integrates with {@link AuditLedgerService} and Micrometer metrics as specified
 * by NLTI-002 acceptance criteria AC5 and AC6.
 *
 * Issue: NLTI-002
 *
 * @see IntentParserServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class IntentAuditIntegrationTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000320");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000321");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000322");

    @Mock
    private NltiIntentRepository intentRepository;

    @Mock
    private AuditLedgerService auditLedgerService;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Clock clock;

    @InjectMocks
    private IntentParserServiceImpl service;

    @BeforeEach
    void setUp() {
        // Stub clock to prevent NullPointerException from OffsetDateTime.now(clock)
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-03-12T10:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        // Stub repository for resolve() tests
        NltiIntent existingIntent = new NltiIntent();
        existingIntent.setId(INTENT_ID);
        existingIntent.setSessionId(SESSION_ID);
        existingIntent.setCorrelationId(CORRELATION_ID);
        existingIntent.setIntentType(NltiIntentType.UNKNOWN);
        existingIntent.setStatus(NltiIntentStatus.NEEDS_CLARIFICATION);
        existingIntent.setRiskLevel(NltiRiskLevel.LOW);
        existingIntent.setSlotsJson("[]");
        existingIntent.setClarificationQuestionsJson("[]");
        lenient().when(intentRepository.findById(INTENT_ID)).thenReturn(Optional.of(existingIntent));
        lenient()
                .when(intentRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stub meterRegistry to prevent NPE from lazy counter initialization
        Counter parseCounter = mock(Counter.class);
        Counter clariCounter = mock(Counter.class);
        lenient().when(meterRegistry.counter("nlt.intent.parse.count")).thenReturn(parseCounter);
        lenient().when(meterRegistry.counter("nlt.intent.clarification.count")).thenReturn(clariCounter);
    }

    // ─── AC6: audit integration ───────────────────────────────────────────────

    /**
     * parse() must call {@link AuditLedgerService#append} exactly once with an
     * event whose eventType is {@code NltiAuditEventType.INTENT}, carrying the
     * correct correlationId and sessionId.
     */
    @Test
    @DisplayName("parse() should call AuditLedgerService.append with INTENT event type")
    void parse_shouldCallAuditLedgerWithIntentEvent() {
        // Issue NLTI-002: AC6 — parse() must trigger AuditLedgerService.append(INTENT event)
        service.parse("check stock", SESSION_ID, CORRELATION_ID);

        ArgumentCaptor<NltiAuditEvent> captor = ArgumentCaptor.forClass(NltiAuditEvent.class);
        verify(auditLedgerService, times(1)).append(captor.capture());
        NltiAuditEvent captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.getId()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(captured.getEventType()).isEqualTo(NltiAuditEventType.INTENT);
        org.assertj.core.api.Assertions.assertThat(captured.getCorrelationId()).isEqualTo(CORRELATION_ID);
        org.assertj.core.api.Assertions.assertThat(captured.getSessionId()).isEqualTo(SESSION_ID);
    }

    // ─── AC5: metric emission — parse ────────────────────────────────────────

    /**
     * parse() must increment the {@code nlt.intent.parse.count} Micrometer counter
     * on every invocation.
     */
    @Test
    @DisplayName("parse() metric counter nlt.intent.parse.count should be incremented")
    void parse_shouldEmitIntentParseCountMetric() {
        // Issue NLTI-002: AC5 — counter "nlt.intent.parse.count" incremented per parse()
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter("nlt.intent.parse.count")).thenReturn(counter);

        service.parse("list items", SESSION_ID, CORRELATION_ID);

        verify(counter, times(1)).increment();
    }

    // ─── AC5: resolve audit event ─────────────────────────────────────────────

    /**
     * resolve() must call {@link AuditLedgerService#append} exactly once with an
     * INTENT event carrying the correct correlationId and sessionId.
     */
    @Test
    @DisplayName("resolve() should call AuditLedgerService.append with INTENT event type")
    void resolve_shouldCallAuditLedgerWithIntentEvent() {
        // Issue NLTI-002: AC5 — resolve() must trigger AuditLedgerService.append(INTENT event)
        var response = new ClarificationResponseDTO(INTENT_ID, "inventory", "inventory check");

        service.resolve(INTENT_ID, response);

        ArgumentCaptor<NltiAuditEvent> captor = ArgumentCaptor.forClass(NltiAuditEvent.class);
        verify(auditLedgerService, times(1)).append(captor.capture());
        NltiAuditEvent captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.getId()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(captured.getEventType()).isEqualTo(NltiAuditEventType.INTENT);
        org.assertj.core.api.Assertions.assertThat(captured.getCorrelationId()).isEqualTo(CORRELATION_ID);
        org.assertj.core.api.Assertions.assertThat(captured.getSessionId()).isEqualTo(SESSION_ID);
    }

    // ─── AC5: metric emission — clarification ────────────────────────────────

    /**
     * resolve() must increment the {@code nlt.intent.clarification.count} Micrometer
     * counter when the intent remains in a pending clarification state.
     */
    @Test
    @DisplayName("resolve() clarification metric nlt.intent.clarification.count incremented when still pending")
    void resolve_shouldEmitClarificationCountMetric() {
        // Issue NLTI-002: AC6 — counter "nlt.intent.clarification.count" incremented when still pending
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter("nlt.intent.clarification.count")).thenReturn(counter);
        // Pass only selectedOption without answer → resolve() sets PENDING_CLARIFICATION
        var response = new ClarificationResponseDTO(INTENT_ID, "inventory", null);

        service.resolve(INTENT_ID, response);

        verify(counter, times(1)).increment();
    }
}
