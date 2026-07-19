package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessorSettlement;
import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementStatus;
import com.positivity.accounting.internal.repository.ExtPaymentSettlementConfigRepository;
import com.positivity.accounting.internal.repository.ProcessorSettlementLineRepository;
import com.positivity.accounting.internal.repository.ProcessorSettlementRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.service.SettlementReconciliationServiceImpl;
import com.positivity.domainevents.payment.SettlementReportedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Unit tests for settlement matching and the review workflow (story F1c, issue #963). */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementReconciliationService")
class SettlementReconciliationServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    private Clock clock = TEST_CLOCK;

    @Mock
    private ProcessorSettlementRepository settlementRepository;

    @Mock
    private ProcessorSettlementLineRepository lineRepository;

    @Mock
    private ExtPaymentSettlementConfigRepository configRepository;

    @Mock
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private GLPostingService glPostingService;

    @InjectMocks
    private SettlementReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "writeOffThreshold", new BigDecimal("25.00"));
        lenient().when(lineRepository.save(any(ProcessorSettlementLine.class))).thenAnswer(i -> i.getArgument(0));
        lenient()
                .when(settlementRepository.save(any(ProcessorSettlement.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    private SettlementReportedV1 settlement(UUID eventId, List<SettlementReportedV1.Line> lines) {
        return new SettlementReportedV1(
                eventId,
                1,
                "stl_123",
                "STRIPE",
                LocalDate.of(2026, 6, 15),
                "USD",
                new BigDecimal("1000.00"),
                new BigDecimal("30.00"),
                new BigDecimal("970.00"),
                lines,
                null);
    }

    private SettlementReportedV1.Line chargeLine(String paymentRef, BigDecimal gross) {
        return new SettlementReportedV1.Line(
                "li_" + paymentRef, SettlementReportedV1.LineType.CHARGE, gross, null, null, paymentRef);
    }

    @Test
    @DisplayName("ingest matches a CHARGE line to a receivable payment on gross and enqueues posting")
    void ingest_matchesCharge() {
        UUID eventId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        SettlementReportedV1 payload =
                settlement(eventId, List.of(chargeLine(sourceEventId.toString(), new BigDecimal("1000.00"))));

        when(settlementRepository.existsByEventId(eventId)).thenReturn(false);
        when(settlementRepository.existsById("stl_123")).thenReturn(false);
        when(configRepository.findById("STRIPE")).thenReturn(Optional.empty());
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(paymentId);
        payment.setTotalAmount(new BigDecimal("1000.00"));
        payment.setSourceEventId(sourceEventId);
        when(receivablePaymentRepository.findBySourceEventId(sourceEventId)).thenReturn(Optional.of(payment));

        service.ingestSettlement(payload);

        ArgumentCaptor<ProcessorSettlementLine> lineCaptor = ArgumentCaptor.forClass(ProcessorSettlementLine.class);
        verify(lineRepository).save(lineCaptor.capture());
        ProcessorSettlementLine line = lineCaptor.getValue();
        assertThat(line.getMatchStatus()).isEqualTo(SettlementLineMatchStatus.MATCHED);
        assertThat(line.getMatchedPaymentId()).isEqualTo(paymentId);
        verify(outboxService).saveToOutbox(any(), eq("ProcessorSettlement"), any(), anyString(), any());
    }

    @Test
    @DisplayName("ingest leaves a CHARGE line UNMATCHED when no payment matches")
    void ingest_unmatchedWhenNoPayment() {
        UUID eventId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        SettlementReportedV1 payload =
                settlement(eventId, List.of(chargeLine(sourceEventId.toString(), new BigDecimal("1000.00"))));

        when(settlementRepository.existsByEventId(eventId)).thenReturn(false);
        when(settlementRepository.existsById("stl_123")).thenReturn(false);
        when(configRepository.findById("STRIPE")).thenReturn(Optional.empty());
        when(receivablePaymentRepository.findBySourceEventId(sourceEventId)).thenReturn(Optional.empty());

        service.ingestSettlement(payload);

        ArgumentCaptor<ProcessorSettlementLine> lineCaptor = ArgumentCaptor.forClass(ProcessorSettlementLine.class);
        verify(lineRepository).save(lineCaptor.capture());
        assertThat(lineCaptor.getValue().getMatchStatus()).isEqualTo(SettlementLineMatchStatus.UNMATCHED);
    }

    @Test
    @DisplayName("ingest is idempotent on eventId — a replay does not re-persist")
    void ingest_idempotent() {
        UUID eventId = UUID.randomUUID();
        SettlementReportedV1 payload = settlement(eventId, List.of());
        when(settlementRepository.existsByEventId(eventId)).thenReturn(true);

        service.ingestSettlement(payload);

        verify(settlementRepository, never()).save(any());
        verify(outboxService, never()).saveToOutbox(any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("writeOffLine rejects amounts above the configured threshold")
    void writeOff_aboveThresholdRejected() {
        UUID lineId = UUID.randomUUID();
        ProcessorSettlementLine line = ProcessorSettlementLine.builder()
                .lineId(lineId)
                .settlementId("stl_123")
                .grossAmount(new BigDecimal("50.00"))
                .matchStatus(SettlementLineMatchStatus.UNMATCHED)
                .build();
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));

        assertThatThrownBy(() -> service.writeOffLine(lineId, "cannot identify"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds threshold");
    }

    @Test
    @DisplayName("writeOffLine below threshold posts a reversible adjustment and marks WRITTEN_OFF")
    void writeOff_belowThresholdPosts() {
        UUID lineId = UUID.randomUUID();
        ProcessorSettlementLine line = ProcessorSettlementLine.builder()
                .lineId(lineId)
                .settlementId("stl_123")
                .grossAmount(new BigDecimal("10.00"))
                .matchStatus(SettlementLineMatchStatus.UNMATCHED)
                .build();
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));
        ProcessorSettlement settlement = ProcessorSettlement.builder()
                .settlementId("stl_123")
                .settlementDate(LocalDate.of(2026, 6, 15))
                .matchedGross(BigDecimal.ZERO)
                .unmatchedCount(1)
                .status(SettlementStatus.RECEIVED)
                .build();
        when(settlementRepository.findById("stl_123")).thenReturn(Optional.of(settlement));
        when(glMappingResolver.resolveGLAccount(anyString(), anyString(), any()))
                .thenReturn(UUID.randomUUID());
        com.positivity.accounting.internal.entity.JournalEntry posted =
                new com.positivity.accounting.internal.entity.JournalEntry();
        posted.setJournalEntryId(UUID.randomUUID());
        when(glPostingService.postSettlementWriteOff(any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(posted);

        ProcessorSettlementLine result = service.writeOffLine(lineId, "unidentifiable micro-deposit");

        assertThat(result.getMatchStatus()).isEqualTo(SettlementLineMatchStatus.WRITTEN_OFF);
        assertThat(result.getWriteoffReason()).isEqualTo("unidentifiable micro-deposit");
        verify(glPostingService).postSettlementWriteOff(any(), any(), any(), any(), any(), anyString(), any());
    }
}
