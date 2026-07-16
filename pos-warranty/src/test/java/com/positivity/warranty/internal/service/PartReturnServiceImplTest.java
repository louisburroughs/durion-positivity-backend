package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.warranty.WarrantyPartReturnRequestedV1;
import com.positivity.domainevents.warranty.WarrantyPartReturnShippedV1;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.dto.PartReturnCreateRequest;
import com.positivity.warranty.internal.dto.PartReturnResponse;
import com.positivity.warranty.internal.dto.PartReturnUpdateRequest;
import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
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
 * Part-return (RMA) child state machine (PRD §3.8): AWAITING_PART → ON_HOLD → SHIPPED →
 * RECEIVED_BY_VENDOR, or → SCRAPPED/CLOSED per disposition; at most one per claim line. Illegal
 * moves raise {@code IllegalClaimStateException} (→ 409 ApiError with nextAction).
 */
@ExtendWith(MockitoExtension.class)
class PartReturnServiceImplTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000301");
    private static final UUID LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000302");
    private static final UUID PART_RETURN_ID = UUID.fromString("018f0000-0000-7000-8000-000000000303");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000304");
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private PartReturnRepository partReturnRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private ClaimSnapshotPublisher claimSnapshotPublisher;

    private PartReturnServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PartReturnServiceImpl(
                claimRepository,
                partReturnRepository,
                claimSnapshotPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                entityManager,
                outboxEventWriterProvider);
    }

    private static WarrantyClaim claimWithLine(ClaimStatus status) {
        WarrantyClaim claim = WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode("WC-2026-000321")
                .claimType(ClaimType.ROAD_HAZARD)
                .status(status)
                .customerId(UUID.fromString("018f0000-0000-7000-8000-000000000305"))
                .version(2L)
                .build();
        WarrantyClaimLine line = WarrantyClaimLine.builder()
                .id(LINE_ID)
                .sourceType(LineSourceType.INVOICE_LINE)
                .productEntityId(PRODUCT_ID)
                .serialNumber("DOT-XYZ")
                .build();
        claim.addLine(line);
        return claim;
    }

    private static PartReturn partReturn(PartReturnStatus status, PartReturnDisposition disposition) {
        return PartReturn.builder()
                .id(PART_RETURN_ID)
                .claimId(CLAIM_ID)
                .claimLineId(LINE_ID)
                .status(status)
                .disposition(disposition)
                .build();
    }

    private void stubSaveEcho() {
        when(partReturnRepository.save(any(PartReturn.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    class Create {

        @Test
        void createsAwaitingPartAndEmitsRequestedEvent() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claimWithLine(ClaimStatus.APPROVED)));
            when(partReturnRepository.findByClaimLineId(LINE_ID)).thenReturn(Optional.empty());
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);

            PartReturnResponse response = service.create(
                    CLAIM_ID,
                    new PartReturnCreateRequest(
                            LINE_ID, PartReturnDisposition.RETURN_TO_VENDOR, "RMA-77", "shelf B-3"));

            assertThat(response.status()).isEqualTo(PartReturnStatus.AWAITING_PART);
            assertThat(response.claimId()).isEqualTo(CLAIM_ID);
            assertThat(response.claimLineId()).isEqualTo(LINE_ID);
            assertThat(response.rmaNumber()).isEqualTo("RMA-77");

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
            DomainEventEnvelope<?> envelope = captor.getValue();
            assertThat(envelope.eventType()).isEqualTo(WarrantyPartReturnRequestedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(CLAIM_ID);
            WarrantyPartReturnRequestedV1 payload = (WarrantyPartReturnRequestedV1) envelope.payload();
            assertThat(payload.productEntityId()).isEqualTo(PRODUCT_ID);
            assertThat(payload.serialNumber()).isEqualTo("DOT-XYZ");
            assertThat(payload.disposition()).isEqualTo("RETURN_TO_VENDOR");
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void rejectsSecondReturnForSameLine() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claimWithLine(ClaimStatus.APPROVED)));
            when(partReturnRepository.findByClaimLineId(LINE_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.HOLD_FOR_INSPECTION)));

            assertThatThrownBy(() -> service.create(
                            CLAIM_ID,
                            new PartReturnCreateRequest(LINE_ID, PartReturnDisposition.RETURN_TO_VENDOR, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(PartReturnServiceImpl.ALREADY_EXISTS_CODE);
                        assertThat(e.getNextAction()).contains(PART_RETURN_ID.toString());
                    });
        }

        @Test
        void rejectsLineNotOnClaim() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claimWithLine(ClaimStatus.APPROVED)));

            assertThatThrownBy(() -> service.create(
                            CLAIM_ID,
                            new PartReturnCreateRequest(
                                    UUID.fromString("018f0000-0000-7000-8000-0000000003ff"),
                                    PartReturnDisposition.RETURN_TO_VENDOR,
                                    null,
                                    null)))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(PartReturnServiceImpl.LINE_NOT_FOUND_CODE));
        }

        @Test
        void rejectsTerminalClaim() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claimWithLine(ClaimStatus.CLOSED)));

            assertThatThrownBy(() -> service.create(
                            CLAIM_ID,
                            new PartReturnCreateRequest(LINE_ID, PartReturnDisposition.RETURN_TO_VENDOR, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(PartReturnServiceImpl.CLAIM_STATE_CODE));
        }

        @Test
        void missingClaimIs404() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(
                            CLAIM_ID,
                            new PartReturnCreateRequest(LINE_ID, PartReturnDisposition.RETURN_TO_VENDOR, null, null)))
                    .isInstanceOf(WarrantyNotFoundException.class);
        }
    }

    @Nested
    class Update {

        private static PartReturnUpdateRequest statusOnly(PartReturnStatus status) {
            return new PartReturnUpdateRequest(status, null, null, null, null, null, null);
        }

        @Test
        void awaitingPartToOnHoldIsLegal() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.HOLD_FOR_INSPECTION)));
            stubSaveEcho();

            PartReturnResponse response = service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.ON_HOLD));

            assertThat(response.status()).isEqualTo(PartReturnStatus.ON_HOLD);
            verify(outboxEventWriter, never()).publish(any(), any());
        }

        @Test
        void onHoldToShippedSetsShippedAtAndEmitsShippedEvent() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(
                            Optional.of(partReturn(PartReturnStatus.ON_HOLD, PartReturnDisposition.RETURN_TO_VENDOR)));
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claimWithLine(ClaimStatus.SETTLED)));
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);

            PartReturnResponse response = service.update(
                    PART_RETURN_ID,
                    new PartReturnUpdateRequest(PartReturnStatus.SHIPPED, null, "UPS", "1Z999", null, null, null));

            assertThat(response.status()).isEqualTo(PartReturnStatus.SHIPPED);
            assertThat(response.shippedAt()).isEqualTo(NOW);
            assertThat(response.carrier()).isEqualTo("UPS");

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
            WarrantyPartReturnShippedV1 payload =
                    (WarrantyPartReturnShippedV1) captor.getValue().payload();
            assertThat(payload.carrier()).isEqualTo("UPS");
            assertThat(payload.trackingNumber()).isEqualTo("1Z999");
            assertThat(payload.shippedAt()).isEqualTo(NOW);
        }

        @Test
        void awaitingPartStraightToShippedIsLegalAndHonorsProvidedShippedAt() {
            Instant shipped = Instant.parse("2026-07-14T09:30:00Z");
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.RETURN_TO_VENDOR)));
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

            PartReturnResponse response = service.update(
                    PART_RETURN_ID,
                    new PartReturnUpdateRequest(PartReturnStatus.SHIPPED, null, null, null, shipped, null, null));

            assertThat(response.status()).isEqualTo(PartReturnStatus.SHIPPED);
            assertThat(response.shippedAt()).isEqualTo(shipped);
        }

        @Test
        void shippedToReceivedByVendorIsLegal() {
            PartReturn shipped = partReturn(PartReturnStatus.SHIPPED, PartReturnDisposition.RETURN_TO_VENDOR);
            shipped.setShippedAt(NOW);
            when(partReturnRepository.findById(PART_RETURN_ID)).thenReturn(Optional.of(shipped));
            stubSaveEcho();

            PartReturnResponse response =
                    service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.RECEIVED_BY_VENDOR));

            assertThat(response.status()).isEqualTo(PartReturnStatus.RECEIVED_BY_VENDOR);
        }

        @Test
        void awaitingPartCannotJumpToReceivedByVendor() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.RETURN_TO_VENDOR)));

            assertThatThrownBy(() -> service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.RECEIVED_BY_VENDOR)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(PartReturnServiceImpl.ILLEGAL_TRANSITION_CODE);
                        assertThat(e.getNextAction()).contains("AWAITING_PART");
                    });
        }

        @Test
        void shippingRequiresReturnToVendorDisposition() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.HOLD_FOR_INSPECTION)));

            assertThatThrownBy(() -> service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.SHIPPED)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(PartReturnServiceImpl.DISPOSITION_CODE));
        }

        @Test
        void shippingAllowedWhenDispositionFixedInSameRequest() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.HOLD_FOR_INSPECTION)));
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claimWithLine(ClaimStatus.SETTLED)));
            stubSaveEcho();
            when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);

            PartReturnResponse response = service.update(
                    PART_RETURN_ID,
                    new PartReturnUpdateRequest(
                            PartReturnStatus.SHIPPED,
                            PartReturnDisposition.RETURN_TO_VENDOR,
                            "FedEx",
                            null,
                            null,
                            null,
                            null));

            assertThat(response.status()).isEqualTo(PartReturnStatus.SHIPPED);
            assertThat(response.disposition()).isEqualTo(PartReturnDisposition.RETURN_TO_VENDOR);
        }

        @Test
        void scrapRequiresScrapAuthorizedDisposition() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(
                            Optional.of(partReturn(PartReturnStatus.ON_HOLD, PartReturnDisposition.RETURN_TO_VENDOR)));

            assertThatThrownBy(() -> service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.SCRAPPED)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(PartReturnServiceImpl.DISPOSITION_CODE));
        }

        @Test
        void scrapAllowedWithScrapAuthorizedDisposition() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(
                            Optional.of(partReturn(PartReturnStatus.ON_HOLD, PartReturnDisposition.SCRAP_AUTHORIZED)));
            stubSaveEcho();

            PartReturnResponse response = service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.SCRAPPED));

            assertThat(response.status()).isEqualTo(PartReturnStatus.SCRAPPED);
        }

        @Test
        void closeIsLegalFromAnyNonTerminalState() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.CUSTOMER_RETAINED)));
            stubSaveEcho();

            PartReturnResponse response = service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.CLOSED));

            assertThat(response.status()).isEqualTo(PartReturnStatus.CLOSED);
        }

        @Test
        void terminalPartReturnRejectsAnyUpdate() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.RECEIVED_BY_VENDOR, PartReturnDisposition.RETURN_TO_VENDOR)));

            assertThatThrownBy(() -> service.update(
                            PART_RETURN_ID, new PartReturnUpdateRequest(null, null, null, "1Z000", null, null, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(PartReturnServiceImpl.TERMINAL_CODE));
        }

        @Test
        void detailOnlyUpdateKeepsStatus() {
            when(partReturnRepository.findById(PART_RETURN_ID))
                    .thenReturn(Optional.of(
                            partReturn(PartReturnStatus.ON_HOLD, PartReturnDisposition.HOLD_FOR_INSPECTION)));
            stubSaveEcho();

            PartReturnResponse response = service.update(
                    PART_RETURN_ID,
                    new PartReturnUpdateRequest(null, null, null, null, null, "RMA-88", "moved to shelf C-1"));

            assertThat(response.status()).isEqualTo(PartReturnStatus.ON_HOLD);
            assertThat(response.rmaNumber()).isEqualTo("RMA-88");
            assertThat(response.holdLocationNote()).isEqualTo("moved to shelf C-1");
            verify(outboxEventWriter, never()).publish(any(), any());
        }

        @Test
        void missingPartReturnIs404() {
            when(partReturnRepository.findById(PART_RETURN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(PART_RETURN_ID, statusOnly(PartReturnStatus.ON_HOLD)))
                    .isInstanceOf(WarrantyNotFoundException.class);
        }
    }

    @Nested
    class Worklist {

        @Test
        void filtersByStatus() {
            when(partReturnRepository.findByStatus(PartReturnStatus.AWAITING_PART))
                    .thenReturn(List.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.HOLD_FOR_INSPECTION)));

            List<PartReturnResponse> result = service.worklist(PartReturnStatus.AWAITING_PART);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().status()).isEqualTo(PartReturnStatus.AWAITING_PART);
        }

        @Test
        void unfilteredReturnsAll() {
            when(partReturnRepository.findAll())
                    .thenReturn(List.of(
                            partReturn(PartReturnStatus.AWAITING_PART, PartReturnDisposition.HOLD_FOR_INSPECTION),
                            partReturn(PartReturnStatus.SHIPPED, PartReturnDisposition.RETURN_TO_VENDOR)));

            assertThat(service.worklist(null)).hasSize(2);
        }
    }
}
