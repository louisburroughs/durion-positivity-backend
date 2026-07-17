package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.warranty.WarrantyReimbursementResolvedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementSubmittedV1;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.dto.ReimbursementResponse;
import com.positivity.warranty.internal.dto.ReimbursementSubmitRequest;
import com.positivity.warranty.internal.dto.ReimbursementUpdateRequest;
import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Vendor-reimbursement child state machine (PRD §3.7): NOT_SUBMITTED → SUBMITTED → APPROVED |
 * PARTIALLY_APPROVED | DENIED, then CREDIT_RECEIVED; any non-terminal → WRITTEN_OFF. Illegal
 * moves raise {@code IllegalClaimStateException} (→ 409 ApiError with nextAction).
 */
@ExtendWith(MockitoExtension.class)
class ReimbursementServiceImplTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000201");
    private static final UUID PROVIDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000202");
    private static final UUID AP_VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-000000000203");
    private static final UUID REIMBURSEMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000204");
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private WarrantyProviderRepository providerRepository;

    @Mock
    private VendorReimbursementRepository reimbursementRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private ClaimSnapshotPublisher claimSnapshotPublisher;

    private ReimbursementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReimbursementServiceImpl(
                claimRepository,
                providerRepository,
                reimbursementRepository,
                claimSnapshotPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                entityManager,
                outboxEventWriterProvider);
    }

    private static WarrantyClaim claim(ClaimStatus status) {
        return WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode("WC-2026-000123")
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .status(status)
                .customerId(UUID.fromString("018f0000-0000-7000-8000-000000000205"))
                .providerId(PROVIDER_ID)
                .version(3L)
                .build();
    }

    private static WarrantyProvider provider(ProviderType type) {
        return WarrantyProvider.builder()
                .id(PROVIDER_ID)
                .name("Michelin")
                .providerType(type)
                .apVendorId(AP_VENDOR_ID)
                .build();
    }

    private static VendorReimbursement reimbursement(ReimbursementStatus status) {
        return VendorReimbursement.builder()
                .id(REIMBURSEMENT_ID)
                .claimId(CLAIM_ID)
                .providerId(PROVIDER_ID)
                .status(status)
                .amountRequested(new BigDecimal("120.0000"))
                .build();
    }

    private void stubSaveEcho() {
        when(reimbursementRepository.save(any(VendorReimbursement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubSaveAndFlushEcho() {
        when(reimbursementRepository.saveAndFlush(any(VendorReimbursement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    class Submit {

        @Test
        void submitsOnApprovedClaimAndEmitsSubmittedEvent() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));
            when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
            stubSaveAndFlushEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);

            ReimbursementResponse response = service.submit(
                    CLAIM_ID, new ReimbursementSubmitRequest(new BigDecimal("120.0000"), "MICH-42", "sent via portal"));

            assertThat(response.status()).isEqualTo(ReimbursementStatus.SUBMITTED);
            assertThat(response.amountRequested()).isEqualByComparingTo("120.0000");
            assertThat(response.vendorClaimReference()).isEqualTo("MICH-42");
            assertThat(response.submittedAt()).isEqualTo(NOW);
            assertThat(response.submittedBy()).isNotBlank();

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
            DomainEventEnvelope<?> envelope = captor.getValue();
            assertThat(envelope.eventType()).isEqualTo(WarrantyReimbursementSubmittedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(CLAIM_ID);
            WarrantyReimbursementSubmittedV1 payload = (WarrantyReimbursementSubmittedV1) envelope.payload();
            assertThat(payload.apVendorId()).isEqualTo(AP_VENDOR_ID);
            assertThat(payload.claimCode()).isEqualTo("WC-2026-000123");
            assertThat(payload.amountRequested()).isEqualByComparingTo("120.0000");
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void reusesExistingNotSubmittedRecordOnSettledClaim() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.SETTLED)));
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.NOT_SUBMITTED)));
            stubSaveAndFlushEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            ReimbursementResponse response =
                    service.submit(CLAIM_ID, new ReimbursementSubmitRequest(new BigDecimal("55"), null, null));

            assertThat(response.id()).isEqualTo(REIMBURSEMENT_ID);
            assertThat(response.status()).isEqualTo(ReimbursementStatus.SUBMITTED);
            verify(outboxEventWriter, never()).publish(any(), any());
        }

        @Test
        void rejectsWhenClaimNotApprovedOrSettled() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.IN_REVIEW)));

            assertThatThrownBy(
                            () -> service.submit(CLAIM_ID, new ReimbursementSubmitRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(ReimbursementServiceImpl.CLAIM_STATE_CODE);
                        assertThat(e.getNextAction()).isNotBlank();
                    });
        }

        @Test
        void rejectsDealerFundedProviderAsNotApplicable() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.DEALER)));

            assertThatThrownBy(
                            () -> service.submit(CLAIM_ID, new ReimbursementSubmitRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(ReimbursementServiceImpl.NOT_APPLICABLE_CODE));
        }

        @Test
        void rejectsWhenClaimHasNoProvider() {
            WarrantyClaim noProvider = claim(ClaimStatus.APPROVED);
            noProvider.setProviderId(null);
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(noProvider));

            assertThatThrownBy(
                            () -> service.submit(CLAIM_ID, new ReimbursementSubmitRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(ReimbursementServiceImpl.NO_PROVIDER_CODE));
        }

        @Test
        void rejectsResubmissionOfAlreadySubmitted() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.SUBMITTED)));

            assertThatThrownBy(
                            () -> service.submit(CLAIM_ID, new ReimbursementSubmitRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(ReimbursementServiceImpl.ALREADY_SUBMITTED_CODE));
        }

        @Test
        void missingClaimIs404() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(
                            () -> service.submit(CLAIM_ID, new ReimbursementSubmitRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOf(WarrantyNotFoundException.class);
        }

        @Test
        void concurrentSubmitLosingTheUniqueConstraintRaceIs409AndPublishesNothing() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));
            when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
            when(reimbursementRepository.saveAndFlush(any(VendorReimbursement.class)))
                    .thenThrow(new DataIntegrityViolationException("vendor_reimbursement_claim_key"));

            assertThatThrownBy(
                            () -> service.submit(CLAIM_ID, new ReimbursementSubmitRequest(BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(ReimbursementServiceImpl.ALREADY_SUBMITTED_CODE));
            verify(outboxEventWriter, never()).publish(any(), any());
        }
    }

    @Nested
    class Update {

        private void stubClaimAndExisting(ReimbursementStatus current) {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.SETTLED)));
            when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of(reimbursement(current)));
        }

        @Test
        void submittedToApprovedSetsResolvedAtAndEmitsResolvedEvent() {
            stubClaimAndExisting(ReimbursementStatus.SUBMITTED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));

            ReimbursementResponse response = service.update(
                    CLAIM_ID,
                    new ReimbursementUpdateRequest(
                            ReimbursementStatus.APPROVED, new BigDecimal("100.0000"), null, "approved by vendor"));

            assertThat(response.status()).isEqualTo(ReimbursementStatus.APPROVED);
            assertThat(response.amountApproved()).isEqualByComparingTo("100.0000");
            assertThat(response.resolvedAt()).isEqualTo(NOW);

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
            WarrantyReimbursementResolvedV1 payload =
                    (WarrantyReimbursementResolvedV1) captor.getValue().payload();
            assertThat(payload.status()).isEqualTo("APPROVED");
            assertThat(payload.apVendorId()).isEqualTo(AP_VENDOR_ID);
        }

        @Test
        void submittedToDeniedNeedsNoAmountAndEmitsResolvedEvent() {
            stubClaimAndExisting(ReimbursementStatus.SUBMITTED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));

            ReimbursementResponse response = service.update(
                    CLAIM_ID, new ReimbursementUpdateRequest(ReimbursementStatus.DENIED, null, null, "worn out"));

            assertThat(response.status()).isEqualTo(ReimbursementStatus.DENIED);
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), any());
        }

        @Test
        void approvedToCreditReceivedSetsCreditReceivedAt() {
            stubClaimAndExisting(ReimbursementStatus.APPROVED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            ReimbursementResponse response = service.update(
                    CLAIM_ID,
                    new ReimbursementUpdateRequest(ReimbursementStatus.CREDIT_RECEIVED, null, "CM-9001", null));

            assertThat(response.status()).isEqualTo(ReimbursementStatus.CREDIT_RECEIVED);
            assertThat(response.creditReference()).isEqualTo("CM-9001");
            assertThat(response.creditReceivedAt()).isEqualTo(NOW);
        }

        @Test
        void partiallyApprovedToCreditReceivedIsLegal() {
            stubClaimAndExisting(ReimbursementStatus.PARTIALLY_APPROVED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            ReimbursementResponse response = service.update(
                    CLAIM_ID, new ReimbursementUpdateRequest(ReimbursementStatus.CREDIT_RECEIVED, null, null, null));

            assertThat(response.status()).isEqualTo(ReimbursementStatus.CREDIT_RECEIVED);
        }

        @Test
        void writtenOffFromSubmittedEmitsResolvedEventSoAccountingStopsExpectingTheCredit() {
            stubClaimAndExisting(ReimbursementStatus.SUBMITTED);
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
            when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(provider(ProviderType.MANUFACTURER)));

            ReimbursementResponse response = service.update(
                    CLAIM_ID, new ReimbursementUpdateRequest(ReimbursementStatus.WRITTEN_OFF, null, null, null));

            assertThat(response.status()).isEqualTo(ReimbursementStatus.WRITTEN_OFF);
            assertThat(response.resolvedAt()).isEqualTo(NOW);

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
            WarrantyReimbursementResolvedV1 payload =
                    (WarrantyReimbursementResolvedV1) captor.getValue().payload();
            assertThat(payload.status()).isEqualTo("WRITTEN_OFF");
            assertThat(payload.amountApproved()).isNull();
            assertThat(payload.creditReference()).isNull();
        }

        @Test
        void submittedCannotJumpToCreditReceived() {
            stubClaimAndExisting(ReimbursementStatus.SUBMITTED);

            assertThatThrownBy(() -> service.update(
                            CLAIM_ID,
                            new ReimbursementUpdateRequest(ReimbursementStatus.CREDIT_RECEIVED, null, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(ReimbursementServiceImpl.ILLEGAL_TRANSITION_CODE);
                        assertThat(e.getNextAction()).contains("SUBMITTED");
                    });
        }

        @Test
        void deniedIsTerminalEvenForWriteOff() {
            stubClaimAndExisting(ReimbursementStatus.DENIED);

            assertThatThrownBy(() -> service.update(
                            CLAIM_ID,
                            new ReimbursementUpdateRequest(ReimbursementStatus.WRITTEN_OFF, null, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class);
        }

        @Test
        void notSubmittedCannotBeApprovedDirectly() {
            stubClaimAndExisting(ReimbursementStatus.NOT_SUBMITTED);

            assertThatThrownBy(() -> service.update(
                            CLAIM_ID,
                            new ReimbursementUpdateRequest(ReimbursementStatus.APPROVED, BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class);
        }

        @Test
        void approvalWithoutAmountIsRejected() {
            stubClaimAndExisting(ReimbursementStatus.SUBMITTED);

            assertThatThrownBy(() -> service.update(
                            CLAIM_ID, new ReimbursementUpdateRequest(ReimbursementStatus.APPROVED, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amountApproved");
        }

        @Test
        void submittedIsNotAValidTarget() {
            stubClaimAndExisting(ReimbursementStatus.NOT_SUBMITTED);

            assertThatThrownBy(() -> service.update(
                            CLAIM_ID, new ReimbursementUpdateRequest(ReimbursementStatus.SUBMITTED, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void missingReimbursementIs404() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.SETTLED)));
            when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.update(
                            CLAIM_ID, new ReimbursementUpdateRequest(ReimbursementStatus.APPROVED, null, null, null)))
                    .isInstanceOf(WarrantyNotFoundException.class);
        }
    }

    @Nested
    class Worklist {

        @Test
        void filtersByStatusAndProvider() {
            when(reimbursementRepository.findByStatusAndProviderId(ReimbursementStatus.SUBMITTED, PROVIDER_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.SUBMITTED)));

            List<ReimbursementResponse> result = service.worklist(ReimbursementStatus.SUBMITTED, PROVIDER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().status()).isEqualTo(ReimbursementStatus.SUBMITTED);
        }

        @Test
        void unfilteredReturnsAll() {
            when(reimbursementRepository.findAll())
                    .thenReturn(List.of(
                            reimbursement(ReimbursementStatus.SUBMITTED),
                            reimbursement(ReimbursementStatus.CREDIT_RECEIVED)));

            assertThat(service.worklist(null, null)).hasSize(2);
        }
    }
}
