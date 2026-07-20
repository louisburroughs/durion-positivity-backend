package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.service.SettlementReconciliationService;
import com.positivity.domainevents.payment.SettlementProviderConfigV1;
import com.positivity.domainevents.payment.SettlementReportedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Reliability contract for the settlement Kafka listeners (PR #977 findings 6 and 13):
 *
 * <ul>
 *   <li>Finding 6: {@link SettlementConfigEventsListener} delegates the upsert to
 *       {@link SettlementConfigService} rather than touching the repository directly.
 *   <li>Finding 13: only deserialization/validation failures are skipped-and-marked; an error
 *       thrown by the business call propagates for container retry / DLQ and is NOT marked processed.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Settlement listeners reliability")
class SettlementListenersReliabilityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SettlementConfigService settlementConfigService;

    @Mock
    private SettlementReconciliationService reconciliationService;

    private SettlementConfigEventsListener configListener() {
        return new SettlementConfigEventsListener(CLOCK, mapper, processedEventRepository, settlementConfigService);
    }

    private SettlementEventsListener eventListener() {
        return new SettlementEventsListener(CLOCK, mapper, processedEventRepository, reconciliationService);
    }

    private String envelope(String eventType, String eventId, Object payload) {
        return mapper.writeValueAsString(Map.of("eventType", eventType, "eventId", eventId, "payload", payload));
    }

    private SettlementProviderConfigV1 config() {
        return new SettlementProviderConfigV1(
                UUID.randomUUID(),
                1,
                "STRIPE",
                "Stripe",
                SettlementProviderConfigV1.MatchReferenceField.PAYMENT_REFERENCE,
                new BigDecimal("0.00"),
                SettlementProviderConfigV1.FeeRepresentation.HEADER_ONLY,
                null);
    }

    private SettlementReportedV1 report() {
        return new SettlementReportedV1(
                UUID.randomUUID(),
                1,
                "stl_1",
                "STRIPE",
                LocalDate.of(2026, 6, 15),
                "USD",
                new BigDecimal("100.00"),
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                List.of(),
                null);
    }

    // ---- Finding 6: config listener delegates to the service ----

    @Test
    @DisplayName("config listener delegates a valid event to the config service and marks it processed")
    void config_delegatesAndMarksProcessed() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        String msg = envelope(SettlementProviderConfigV1.EVENT_TYPE, "evt-cfg-1", config());

        configListener().onSettlementConfigEvent(msg);

        verify(settlementConfigService).upsert(any(SettlementProviderConfigV1.class));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("config listener skips a malformed payload and marks it processed without delegating")
    void config_malformedPayloadMarksProcessed() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        String msg = envelope(SettlementProviderConfigV1.EVENT_TYPE, "evt-cfg-2", "not-a-config");

        configListener().onSettlementConfigEvent(msg);

        verify(settlementConfigService, never()).upsert(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    // ---- Finding 13: business errors propagate, are not swallowed/marked ----

    @Test
    @DisplayName("event listener lets an ingest error propagate and does NOT mark the event processed")
    void event_ingestErrorPropagatesUnmarked() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        doThrow(new RuntimeException("boom")).when(reconciliationService).ingestSettlement(any());
        String msg = envelope(SettlementReportedV1.EVENT_TYPE, "evt-rep-1", report());

        assertThatThrownBy(() -> eventListener().onPaymentEvent(msg)).isInstanceOf(RuntimeException.class);

        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("event listener skips a malformed payload and marks it processed without ingesting")
    void event_malformedPayloadMarksProcessed() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        String msg = envelope(SettlementReportedV1.EVENT_TYPE, "evt-rep-2", "not-a-report");

        eventListener().onPaymentEvent(msg);

        verify(reconciliationService, never()).ingestSettlement(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }
}
