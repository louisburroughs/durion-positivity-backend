package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.warranty.WarrantyClaimSnapshotV1;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.enums.ClaimDecision;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.EligibilityResult;
import com.positivity.warranty.internal.enums.LineDisposition;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Assembly of the full {@code warranty.claim.snapshot} aggregate (ADR-0044 R3): every child
 * collection mapped, enum-typed owner fields carried as names, and null-safe behavior on empty
 * children/nullable header fields. Also covers the Kafka-disabled no-op degrade shared with the
 * granular emitters.
 */
@ExtendWith(MockitoExtension.class)
class ClaimSnapshotPublisherTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000501");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000502");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000503");
    private static final UUID LOCATION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000504");
    private static final UUID PROVIDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000505");
    private static final UUID POLICY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000506");
    private static final UUID ORIGIN_INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000507");
    private static final UUID LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000508");
    private static final UUID SETTLEMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000509");
    private static final UUID REIMBURSEMENT_ID = UUID.fromString("018f0000-0000-7000-8000-00000000050a");
    private static final UUID PART_RETURN_ID = UUID.fromString("018f0000-0000-7000-8000-00000000050b");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000050c");
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-07-01T09:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-07-10T10:00:00Z");

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private ClaimSettlementRepository settlementRepository;

    @Mock
    private VendorReimbursementRepository reimbursementRepository;

    @Mock
    private PartReturnRepository partReturnRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private ClaimSnapshotPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ClaimSnapshotPublisher(
                claimRepository,
                settlementRepository,
                reimbursementRepository,
                partReturnRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                entityManager,
                outboxEventWriterProvider);
    }

    private static WarrantyClaim fullClaim() {
        WarrantyClaim claim = WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode("WC-2026-000042")
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .status(ClaimStatus.SETTLED)
                .locationId(LOCATION_ID)
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .vin("1HGCM82633A004352")
                .odometerAtClaim(42_000L)
                .odometerUnit("MILES")
                .originInvoiceId(ORIGIN_INVOICE_ID)
                .originSaleDate(LocalDate.of(2025, 6, 1))
                .originUnverified(false)
                .providerId(PROVIDER_ID)
                .policyId(POLICY_ID)
                .failureDescription("sidewall separation")
                .failureDate(LocalDate.of(2026, 7, 1))
                .eligibilityResult(EligibilityResult.ELIGIBLE)
                .decision(ClaimDecision.APPROVE)
                .decisionReason("covered defect")
                .decidedBy("manager")
                .decidedAt(DECIDED_AT)
                .overrodeSuggestion(true)
                .createdAt(CREATED_AT)
                .updatedAt(NOW)
                .version(7L)
                .build();
        claim.addLine(WarrantyClaimLine.builder()
                .id(LINE_ID)
                .sourceType(LineSourceType.INVOICE_LINE)
                .sourceId(ORIGIN_INVOICE_ID)
                .sku("TIRE-205-55R16")
                .description("All-season tire")
                .serialNumber("DOT-XYZ")
                .quantity(BigDecimal.ONE)
                .originalUnitPrice(new BigDecimal("129.99"))
                .prorationPct(new BigDecimal("0.500000"))
                .amountRequested(new BigDecimal("65.0000"))
                .amountApproved(new BigDecimal("60.0000"))
                .lineDisposition(LineDisposition.APPROVED)
                .build());
        return claim;
    }

    private WarrantyClaimSnapshotV1 publishAndCapturePayload() {
        publisher.publish(CLAIM_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<WarrantyClaimSnapshotV1>> captor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
        DomainEventEnvelope<WarrantyClaimSnapshotV1> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(WarrantyClaimSnapshotV1.EVENT_TYPE);
        assertThat(envelope.schemaVersion()).isEqualTo(WarrantyClaimSnapshotV1.SCHEMA_VERSION);
        assertThat(envelope.aggregateId()).isEqualTo(CLAIM_ID);
        return envelope.payload();
    }

    @Test
    void assemblesFullAggregateWithAllChildrenMapped() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(fullClaim()));
        when(settlementRepository.findByClaimId(CLAIM_ID))
                .thenReturn(List.of(ClaimSettlement.builder()
                        .id(SETTLEMENT_ID)
                        .claimId(CLAIM_ID)
                        .settlementType(SettlementType.PRORATED_CREDIT)
                        .status(SettlementStatus.COMPLETED)
                        .coveredAmount(new BigDecimal("60.0000"))
                        .customerAmount(new BigDecimal("5.0000"))
                        .invoiceId(INVOICE_ID)
                        .build()));
        when(reimbursementRepository.findByClaimId(CLAIM_ID))
                .thenReturn(List.of(VendorReimbursement.builder()
                        .id(REIMBURSEMENT_ID)
                        .claimId(CLAIM_ID)
                        .providerId(PROVIDER_ID)
                        .status(ReimbursementStatus.SUBMITTED)
                        .amountRequested(new BigDecimal("60.0000"))
                        .vendorClaimReference("MICH-42")
                        .build()));
        when(partReturnRepository.findByClaimId(CLAIM_ID))
                .thenReturn(List.of(PartReturn.builder()
                        .id(PART_RETURN_ID)
                        .claimId(CLAIM_ID)
                        .claimLineId(LINE_ID)
                        .status(PartReturnStatus.AWAITING_PART)
                        .disposition(PartReturnDisposition.RETURN_TO_VENDOR)
                        .rmaNumber("RMA-77")
                        .build()));

        WarrantyClaimSnapshotV1 payload = publishAndCapturePayload();

        verify(entityManager).flush();
        assertThat(payload.claimId()).isEqualTo(CLAIM_ID);
        assertThat(payload.claimCode()).isEqualTo("WC-2026-000042");
        assertThat(payload.claimType()).isEqualTo("MANUFACTURER_DEFECT");
        assertThat(payload.status()).isEqualTo("SETTLED");
        assertThat(payload.locationId()).isEqualTo(LOCATION_ID);
        assertThat(payload.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(payload.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(payload.vin()).isEqualTo("1HGCM82633A004352");
        assertThat(payload.odometerAtClaim()).isEqualTo(42_000L);
        assertThat(payload.odometerUnit()).isEqualTo("MILES");
        assertThat(payload.originInvoiceId()).isEqualTo(ORIGIN_INVOICE_ID);
        assertThat(payload.originSaleDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(payload.originUnverified()).isFalse();
        assertThat(payload.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(payload.policyId()).isEqualTo(POLICY_ID);
        assertThat(payload.failureDescription()).isEqualTo("sidewall separation");
        assertThat(payload.failureDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(payload.eligibilityResult()).isEqualTo("ELIGIBLE");
        assertThat(payload.decision()).isEqualTo("APPROVE");
        assertThat(payload.decisionReason()).isEqualTo("covered defect");
        assertThat(payload.decidedBy()).isEqualTo("manager");
        assertThat(payload.decidedAt()).isEqualTo(DECIDED_AT);
        assertThat(payload.overrodeSuggestion()).isTrue();
        assertThat(payload.createdAt()).isEqualTo(CREATED_AT);
        assertThat(payload.updatedAt()).isEqualTo(NOW);
        assertThat(payload.version()).isEqualTo(7L);
        assertThat(payload.snapshotAt()).isEqualTo(NOW);

        assertThat(payload.lines()).hasSize(1);
        WarrantyClaimSnapshotV1.Line line = payload.lines().getFirst();
        assertThat(line.claimLineId()).isEqualTo(LINE_ID);
        assertThat(line.sourceType()).isEqualTo("INVOICE_LINE");
        assertThat(line.sourceId()).isEqualTo(ORIGIN_INVOICE_ID);
        assertThat(line.sourceLineId()).isNull();
        assertThat(line.sku()).isEqualTo("TIRE-205-55R16");
        assertThat(line.description()).isEqualTo("All-season tire");
        assertThat(line.serialNumber()).isEqualTo("DOT-XYZ");
        assertThat(line.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(line.originalUnitPrice()).isEqualByComparingTo("129.99");
        assertThat(line.prorationPct()).isEqualByComparingTo("0.5");
        assertThat(line.amountRequested()).isEqualByComparingTo("65.0000");
        assertThat(line.amountApproved()).isEqualByComparingTo("60.0000");
        assertThat(line.lineDisposition()).isEqualTo("APPROVED");

        assertThat(payload.settlements()).hasSize(1);
        WarrantyClaimSnapshotV1.Settlement settlement = payload.settlements().getFirst();
        assertThat(settlement.settlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(settlement.settlementType()).isEqualTo("PRORATED_CREDIT");
        assertThat(settlement.status()).isEqualTo("COMPLETED");
        assertThat(settlement.coveredAmount()).isEqualByComparingTo("60.0000");
        assertThat(settlement.customerAmount()).isEqualByComparingTo("5.0000");
        assertThat(settlement.invoiceId()).isEqualTo(INVOICE_ID);
        assertThat(settlement.invoiceAdjustmentId()).isNull();
        assertThat(settlement.refundRecordId()).isNull();
        assertThat(settlement.replacementWorkorderId()).isNull();

        assertThat(payload.reimbursements()).hasSize(1);
        WarrantyClaimSnapshotV1.Reimbursement reimbursement =
                payload.reimbursements().getFirst();
        assertThat(reimbursement.reimbursementId()).isEqualTo(REIMBURSEMENT_ID);
        assertThat(reimbursement.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(reimbursement.status()).isEqualTo("SUBMITTED");
        assertThat(reimbursement.amountRequested()).isEqualByComparingTo("60.0000");
        assertThat(reimbursement.amountApproved()).isNull();
        assertThat(reimbursement.vendorClaimReference()).isEqualTo("MICH-42");

        assertThat(payload.partReturns()).hasSize(1);
        WarrantyClaimSnapshotV1.PartReturn partReturn = payload.partReturns().getFirst();
        assertThat(partReturn.partReturnId()).isEqualTo(PART_RETURN_ID);
        assertThat(partReturn.claimLineId()).isEqualTo(LINE_ID);
        assertThat(partReturn.status()).isEqualTo("AWAITING_PART");
        assertThat(partReturn.disposition()).isEqualTo("RETURN_TO_VENDOR");
        assertThat(partReturn.rmaNumber()).isEqualTo("RMA-77");
    }

    @Test
    void aggregateVersionMirrorsClaimVersion() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(fullClaim()));
        when(settlementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
        when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
        when(partReturnRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());

        publisher.publish(CLAIM_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<WarrantyClaimSnapshotV1>> captor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("warranty.events.v1"), captor.capture());
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(7L);
    }

    @Test
    void isNullSafeOnEmptyChildrenAndNullableHeaderFields() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        // Minimal lineless draft: every nullable header field absent, version not yet assigned.
        when(claimRepository.findById(CLAIM_ID))
                .thenReturn(Optional.of(WarrantyClaim.builder()
                        .id(CLAIM_ID)
                        .claimCode("WC-2026-000001")
                        .claimType(ClaimType.ROAD_HAZARD)
                        .status(ClaimStatus.DRAFT)
                        .customerId(CUSTOMER_ID)
                        .build()));
        when(settlementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
        when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
        when(partReturnRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());

        WarrantyClaimSnapshotV1 payload = publishAndCapturePayload();

        assertThat(payload.status()).isEqualTo("DRAFT");
        assertThat(payload.locationId()).isNull();
        assertThat(payload.vehicleId()).isNull();
        assertThat(payload.vin()).isNull();
        assertThat(payload.odometerAtClaim()).isNull();
        assertThat(payload.odometerUnit()).isNull();
        assertThat(payload.originWorkorderId()).isNull();
        assertThat(payload.originInvoiceId()).isNull();
        assertThat(payload.originSaleDate()).isNull();
        assertThat(payload.originUnverified()).isFalse();
        assertThat(payload.providerId()).isNull();
        assertThat(payload.policyId()).isNull();
        assertThat(payload.registrationId()).isNull();
        assertThat(payload.failureDescription()).isNull();
        assertThat(payload.failureDate()).isNull();
        assertThat(payload.eligibilityResult()).isNull();
        assertThat(payload.decision()).isNull();
        assertThat(payload.decisionReason()).isNull();
        assertThat(payload.decidedBy()).isNull();
        assertThat(payload.decidedAt()).isNull();
        assertThat(payload.overrodeSuggestion()).isFalse();
        assertThat(payload.version()).isZero();
        assertThat(payload.lines()).isEmpty();
        assertThat(payload.settlements()).isEmpty();
        assertThat(payload.reimbursements()).isEmpty();
        assertThat(payload.partReturns()).isEmpty();
        assertThat(payload.snapshotAt()).isEqualTo(NOW);
    }

    @Test
    void degradesToNoOpWhenKafkaDisabled() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

        publisher.publish(CLAIM_ID);

        verifyNoInteractions(
                claimRepository, settlementRepository, reimbursementRepository, partReturnRepository, entityManager);
    }

    @Test
    void skipsQuietlyWhenClaimNotFound() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        publisher.publish(CLAIM_ID);

        verify(outboxEventWriter, never()).publish(any(), any());
    }
}
