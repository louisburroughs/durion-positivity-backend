package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.warranty.WarrantyClaimSettledV1;
import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.client.WorkorderClient;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.dto.SettlementResponse;
import com.positivity.warranty.internal.entity.ClaimNote;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.entity.ClaimStatusHistory;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.exception.WarrantyUnprocessableException;
import com.positivity.warranty.internal.repository.ClaimNoteRepository;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Settlement execution (PRD §3.6, §7 step 6): per-type behavior, the loud-failure path
 * (FAILED persisted + integration error rethrown), and the settle-customer-first
 * {@code APPROVED → SETTLED} transition with its audit row.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceImplTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000301");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000302");
    private static final UUID LOCATION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000303");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000304");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000305");
    private static final UUID PAYMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000306");
    private static final UUID ADJUSTMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000307");
    private static final UUID REFUND_ID = UUID.fromString("018f0000-0000-7000-8000-000000000308");
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final BigDecimal COVERED = new BigDecimal("80.0000");
    private static final BigDecimal CUSTOMER = new BigDecimal("20.0000");

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private ClaimSettlementRepository settlementRepository;

    @Mock
    private ClaimStatusHistoryRepository statusHistoryRepository;

    @Mock
    private ClaimNoteRepository noteRepository;

    @Mock
    private InvoiceClient invoiceClient;

    @Mock
    private WorkorderClient workorderClient;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private ClaimSnapshotPublisher claimSnapshotPublisher;

    private SettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SettlementServiceImpl(
                claimRepository,
                settlementRepository,
                statusHistoryRepository,
                noteRepository,
                invoiceClient,
                workorderClient,
                claimSnapshotPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                entityManager,
                outboxEventWriterProvider);
    }

    private static WarrantyClaim claim(ClaimStatus status) {
        return WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode("WC-2026-000042")
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .status(status)
                .customerId(CUSTOMER_ID)
                .locationId(LOCATION_ID)
                .version(5L)
                .build();
    }

    private static SettlementCreateRequest request(SettlementType type) {
        return new SettlementCreateRequest(
                type,
                COVERED,
                CUSTOMER,
                type == SettlementType.REPLACEMENT_WORKORDER ? WORKORDER_ID : null,
                type == SettlementType.REPLACEMENT_WORKORDER || type == SettlementType.NO_ACTION ? null : INVOICE_ID,
                type == SettlementType.REFUND ? PAYMENT_ID : null,
                null);
    }

    private void stubClaim(ClaimStatus status) {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(status)));
    }

    private void stubSaveEcho() {
        when(settlementRepository.save(any(ClaimSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ClaimSettlement lastSavedSettlement() {
        ArgumentCaptor<ClaimSettlement> captor = ArgumentCaptor.forClass(ClaimSettlement.class);
        verify(settlementRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    class Snapshot {

        @Test
        void successfulSettlementPublishesClaimSnapshot() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();

            service.create(CLAIM_ID, request(SettlementType.NO_ACTION));

            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }
    }

    @Nested
    class Preconditions {

        @Test
        void unknownClaimIs404() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(CLAIM_ID, request(SettlementType.NO_ACTION)))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .hasFieldOrPropertyWithValue("code", SettlementServiceImpl.CLAIM_NOT_FOUND_CODE);
            verifyNoInteractions(settlementRepository, invoiceClient, workorderClient);
        }

        @Test
        void nonApprovedClaimIsRejectedWith409() {
            stubClaim(ClaimStatus.IN_REVIEW);

            assertThatThrownBy(() -> service.create(CLAIM_ID, request(SettlementType.NO_ACTION)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .hasFieldOrPropertyWithValue("code", SettlementServiceImpl.CLAIM_STATE_CODE);
            verifyNoInteractions(settlementRepository, invoiceClient, workorderClient, statusHistoryRepository);
        }
    }

    @Nested
    class ReplacementWorkorder {

        @Test
        void linksValidatedWorkorderAndCompletesImmediately() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(workorderClient.getWorkorderDetail(WORKORDER_ID))
                    .thenReturn(Optional.of(new WorkorderClient.WorkorderDetail(
                            WORKORDER_ID, "WO-100", CUSTOMER_ID, null, List.of(), List.of())));
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            SettlementResponse response = service.create(CLAIM_ID, request(SettlementType.REPLACEMENT_WORKORDER));

            assertThat(response.status()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(response.replacementWorkorderId()).isEqualTo(WORKORDER_ID);
            assertThat(response.invoiceId()).isNull();
            verifyNoInteractions(invoiceClient);
        }

        @Test
        void missingWorkorderIdIsValidationError() {
            stubClaim(ClaimStatus.APPROVED);

            SettlementCreateRequest request = new SettlementCreateRequest(
                    SettlementType.REPLACEMENT_WORKORDER, COVERED, CUSTOMER, null, null, null, null);
            assertThatThrownBy(() -> service.create(CLAIM_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("replacementWorkorderId");
            verifyNoInteractions(settlementRepository, workorderClient);
        }

        @Test
        void unresolvableWorkorderIs422AndNothingIsPersisted() {
            stubClaim(ClaimStatus.APPROVED);
            when(workorderClient.getWorkorderDetail(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(CLAIM_ID, request(SettlementType.REPLACEMENT_WORKORDER)))
                    .isInstanceOf(WarrantyUnprocessableException.class)
                    .hasFieldOrPropertyWithValue("code", SettlementServiceImpl.WORKORDER_NOT_FOUND_CODE);
            verifyNoInteractions(settlementRepository, statusHistoryRepository);
            verify(claimRepository, never()).save(any());
        }
    }

    @Nested
    class InvoiceAdjustmentTypes {

        @Test
        void invoiceCreditCreatesAdjustmentWithClaimCodeReference() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(invoiceClient.createAdjustment(
                            eq(INVOICE_ID), eq(COVERED), contains("WC-2026-000042"), any(), eq("WC-2026-000042")))
                    .thenReturn(Optional.of(ADJUSTMENT_ID));
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            SettlementResponse response = service.create(CLAIM_ID, request(SettlementType.INVOICE_CREDIT));

            assertThat(response.status()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(response.invoiceId()).isEqualTo(INVOICE_ID);
            assertThat(response.invoiceAdjustmentId()).isEqualTo(ADJUSTMENT_ID);
        }

        @Test
        void proratedCreditAndGoodwillUseTheSameAdjustmentPath() {
            stubClaim(ClaimStatus.SETTLED);
            stubSaveEcho();
            when(invoiceClient.createAdjustment(eq(INVOICE_ID), eq(COVERED), any(), any(), any()))
                    .thenReturn(Optional.of(ADJUSTMENT_ID));
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            assertThat(service.create(CLAIM_ID, request(SettlementType.PRORATED_CREDIT))
                            .status())
                    .isEqualTo(SettlementStatus.COMPLETED);
            assertThat(service.create(CLAIM_ID, request(SettlementType.GOODWILL))
                            .status())
                    .isEqualTo(SettlementStatus.COMPLETED);
        }

        @Test
        void missingInvoiceIdIsValidationError() {
            stubClaim(ClaimStatus.APPROVED);

            SettlementCreateRequest request = new SettlementCreateRequest(
                    SettlementType.INVOICE_CREDIT, COVERED, CUSTOMER, null, null, null, null);
            assertThatThrownBy(() -> service.create(CLAIM_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invoiceId");
            verifyNoInteractions(settlementRepository, invoiceClient);
        }

        @Test
        void invoiceFailurePersistsFailedSettlementAndRethrows() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(invoiceClient.createAdjustment(any(), any(), any(), any(), any()))
                    .thenThrow(new WarrantyIntegrationException("pos-invoice returned 503"));

            assertThatThrownBy(() -> service.create(CLAIM_ID, request(SettlementType.INVOICE_CREDIT)))
                    .isInstanceOf(WarrantyIntegrationException.class);

            ClaimSettlement saved = lastSavedSettlement();
            assertThat(saved.getStatus()).isEqualTo(SettlementStatus.FAILED);
            assertThat(saved.getInvoiceId()).isEqualTo(INVOICE_ID);
            // The claim must not settle and no event may be queued for a failed settlement.
            verify(claimRepository, never()).save(any());
            verifyNoInteractions(statusHistoryRepository, outboxEventWriter);
        }
    }

    @Nested
    class Refund {

        @Test
        void refundCreatesRefundRecordViaInvoiceService() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(invoiceClient.createRefund(eq(INVOICE_ID), eq(PAYMENT_ID), eq(COVERED), any(), eq("WC-2026-000042")))
                    .thenReturn(Optional.of(REFUND_ID));
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            SettlementResponse response = service.create(CLAIM_ID, request(SettlementType.REFUND));

            assertThat(response.status()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(response.refundRecordId()).isEqualTo(REFUND_ID);
        }

        @Test
        void missingPaymentIdIsValidationError() {
            stubClaim(ClaimStatus.APPROVED);

            SettlementCreateRequest request =
                    new SettlementCreateRequest(SettlementType.REFUND, COVERED, CUSTOMER, null, INVOICE_ID, null, null);
            assertThatThrownBy(() -> service.create(CLAIM_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("paymentId");
            verifyNoInteractions(settlementRepository, invoiceClient);
        }

        @Test
        void refundFailurePersistsFailedSettlementAndRethrows() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(invoiceClient.createRefund(any(), any(), any(), any(), any()))
                    .thenThrow(new WarrantyIntegrationException("refund rejected"));

            assertThatThrownBy(() -> service.create(CLAIM_ID, request(SettlementType.REFUND)))
                    .isInstanceOf(WarrantyIntegrationException.class);
            assertThat(lastSavedSettlement().getStatus()).isEqualTo(SettlementStatus.FAILED);
            verify(claimRepository, never()).save(any());
            verifyNoInteractions(statusHistoryRepository);
        }
    }

    @Nested
    class ClaimTransitionAndAudit {

        @Test
        void firstSettlementMovesApprovedClaimToSettledWithHistoryRow() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            SettlementResponse response = service.create(CLAIM_ID, request(SettlementType.NO_ACTION));

            assertThat(response.claimStatus()).isEqualTo(ClaimStatus.SETTLED);
            ArgumentCaptor<WarrantyClaim> claimCaptor = ArgumentCaptor.forClass(WarrantyClaim.class);
            verify(claimRepository).save(claimCaptor.capture());
            assertThat(claimCaptor.getValue().getStatus()).isEqualTo(ClaimStatus.SETTLED);

            ArgumentCaptor<ClaimStatusHistory> historyCaptor = ArgumentCaptor.forClass(ClaimStatusHistory.class);
            verify(statusHistoryRepository).save(historyCaptor.capture());
            assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(ClaimStatus.SETTLED);
            assertThat(historyCaptor.getValue().getReason()).contains("NO_ACTION");
        }

        @Test
        void additionalSettlementOnSettledClaimLeavesStatusAlone() {
            stubClaim(ClaimStatus.SETTLED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            SettlementResponse response = service.create(CLAIM_ID, request(SettlementType.NO_ACTION));

            assertThat(response.claimStatus()).isEqualTo(ClaimStatus.SETTLED);
            verify(claimRepository, never()).save(any());
            verifyNoInteractions(statusHistoryRepository);
        }

        @Test
        void notesAreRecordedAsClaimNote() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            service.create(
                    CLAIM_ID,
                    new SettlementCreateRequest(
                            SettlementType.NO_ACTION, COVERED, CUSTOMER, null, null, null, "customer waived credit"));

            ArgumentCaptor<ClaimNote> noteCaptor = ArgumentCaptor.forClass(ClaimNote.class);
            verify(noteRepository).save(noteCaptor.capture());
            assertThat(noteCaptor.getValue().getClaimId()).isEqualTo(CLAIM_ID);
            assertThat(noteCaptor.getValue().getNote()).contains("NO_ACTION").contains("customer waived credit");
        }
    }

    @Nested
    class OutboxEvents {

        @Test
        void successfulSettlementQueuesClaimSettledEvent() {
            stubClaim(ClaimStatus.APPROVED);
            stubSaveEcho();
            when(claimRepository.save(any(WarrantyClaim.class))).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
            when(workorderClient.getWorkorderDetail(WORKORDER_ID))
                    .thenReturn(Optional.of(new WorkorderClient.WorkorderDetail(
                            WORKORDER_ID, "WO-100", CUSTOMER_ID, null, List.of(), List.of())));

            service.create(CLAIM_ID, request(SettlementType.REPLACEMENT_WORKORDER));

            verify(entityManager).flush();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<DomainEventEnvelope<WarrantyClaimSettledV1>> envelopeCaptor =
                    ArgumentCaptor.forClass(DomainEventEnvelope.class);
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), envelopeCaptor.capture());
            DomainEventEnvelope<WarrantyClaimSettledV1> envelope = envelopeCaptor.getValue();
            assertThat(envelope.eventType()).isEqualTo(WarrantyClaimSettledV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(CLAIM_ID);
            WarrantyClaimSettledV1 payload = envelope.payload();
            assertThat(payload.claimCode()).isEqualTo("WC-2026-000042");
            assertThat(payload.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(payload.locationId()).isEqualTo(LOCATION_ID);
            assertThat(payload.settlementType()).isEqualTo("REPLACEMENT_WORKORDER");
            assertThat(payload.coveredAmount()).isEqualTo(COVERED);
            assertThat(payload.customerAmount()).isEqualTo(CUSTOMER);
            assertThat(payload.replacementWorkorderId()).isEqualTo(WORKORDER_ID);
            assertThat(payload.settledAt()).isEqualTo(NOW);
        }

        @Test
        void noOpWhenOutboxDisabled() {
            stubClaim(ClaimStatus.SETTLED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            service.create(CLAIM_ID, request(SettlementType.NO_ACTION));

            verifyNoInteractions(outboxEventWriter, entityManager);
        }
    }
}
