package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.warranty.WarrantyClaimSettledV1;
import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.client.WorkorderClient;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.repository.ClaimNoteRepository;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Outbox payload mapping for {@code warranty.claim.settled} (PRD §9.3), focused on the
 * {@link WarrantyClaimSettledV1#locationId()} contract: {@code warranty_claim.location_id}
 * is a nullable column, so the payload must carry the claim's location verbatim — a UUID
 * when present, {@code null} when the claim has no location.
 */
@ExtendWith(MockitoExtension.class)
class SettlementOutboxTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000401");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000402");
    private static final UUID LOCATION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000403");
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
        when(settlementRepository.save(any(ClaimSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
    }

    private void stubClaim(UUID locationId) {
        when(claimRepository.findById(CLAIM_ID))
                .thenReturn(Optional.of(WarrantyClaim.builder()
                        .id(CLAIM_ID)
                        .claimCode("WC-2026-000077")
                        .claimType(ClaimType.MANUFACTURER_DEFECT)
                        .status(ClaimStatus.SETTLED)
                        .customerId(CUSTOMER_ID)
                        .locationId(locationId)
                        .version(3L)
                        .build()));
    }

    private WarrantyClaimSettledV1 settleAndCapturePayload() {
        service.create(
                CLAIM_ID,
                new SettlementCreateRequest(SettlementType.NO_ACTION, COVERED, CUSTOMER, null, null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<WarrantyClaimSettledV1>> envelopeCaptor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("warranty.events.v1"), envelopeCaptor.capture());
        return envelopeCaptor.getValue().payload();
    }

    @Test
    void payloadCarriesClaimLocationWhenPresent() {
        stubClaim(LOCATION_ID);

        WarrantyClaimSettledV1 payload = settleAndCapturePayload();

        assertThat(payload.locationId()).isEqualTo(LOCATION_ID);
        assertThat(payload.claimId()).isEqualTo(CLAIM_ID);
        assertThat(payload.claimCode()).isEqualTo("WC-2026-000077");
        assertThat(payload.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(payload.settlementType()).isEqualTo("NO_ACTION");
        assertThat(payload.coveredAmount()).isEqualTo(COVERED);
        assertThat(payload.customerAmount()).isEqualTo(CUSTOMER);
        assertThat(payload.settledAt()).isEqualTo(NOW);
    }

    @Test
    void payloadCarriesNullLocationWhenClaimHasNone() {
        stubClaim(null);

        WarrantyClaimSettledV1 payload = settleAndCapturePayload();

        assertThat(payload.locationId()).isNull();
        // The rest of the payload must be unaffected by the missing location.
        assertThat(payload.claimId()).isEqualTo(CLAIM_ID);
        assertThat(payload.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(payload.settlementType()).isEqualTo("NO_ACTION");
        assertThat(payload.settledAt()).isEqualTo(NOW);
        // NO_ACTION produces no downstream documents.
        assertThat(payload.invoiceId()).isNull();
        assertThat(payload.refundRecordId()).isNull();
        assertThat(payload.replacementWorkorderId()).isNull();
    }
}
