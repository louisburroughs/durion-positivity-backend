package com.positivity.supplier.internal.workorderauth.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierWorkorderAuthDeniedV1;
import com.positivity.domainevents.supplier.SupplierWorkorderAuthGrantedV1;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What reaches the rest of the platform, and what deliberately does not (CAP-323 #1229).
 *
 * <p>The consumers of these events decide whether a shop may do work under a fleet contract. The
 * cases below are the ones where publishing the wrong thing — or publishing at all — would have a
 * shop either working unpaid or turning away a customer the fleet would have covered.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderAuthorizationPublisher — only vendor answers go out (#1229)")
class WorkorderAuthorizationPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Mock
    private SupplierOutboxEventWriter outboxEventWriter;

    @InjectMocks
    private WorkorderAuthorizationPublisher publisher;

    @Captor
    private ArgumentCaptor<DomainEventEnvelope<?>> envelopeCaptor;

    @Test
    @DisplayName("a grant is published with the vendor's id and ceiling")
    void grantIsPublished() {
        SupplierWorkorderAuthorizationEntity row = row(WorkorderAuthorizationStatus.GRANTED);
        row.setVendorAuthorizationId("WO-42");
        row.setContractReference("C-1");

        publisher.publishIfTerminal(row, NOW);

        verify(outboxEventWriter).publish(eq("supplier.events.v1"), envelopeCaptor.capture());
        Assertions.assertThat(envelopeCaptor.getValue().eventType())
                .isEqualTo(SupplierWorkorderAuthGrantedV1.EVENT_TYPE);
        Assertions.assertThat(envelopeCaptor.getValue().payload())
                .isInstanceOfSatisfying(SupplierWorkorderAuthGrantedV1.class, payload -> {
                    Assertions.assertThat(payload.vendorAuthorizationId()).isEqualTo("WO-42");
                    Assertions.assertThat(payload.contractReference()).isEqualTo("C-1");
                });
    }

    @Test
    @DisplayName("a vendor that does not know the vehicle is published as a denial, with its reason")
    void notFoundIsPublishedAsDenial() {
        // The vendor did answer. For a shop the consequence is a refusal — but the reason text is
        // what says this one is fixable by correcting a fleet number rather than by phoning anyone.
        SupplierWorkorderAuthorizationEntity row = row(WorkorderAuthorizationStatus.NOT_FOUND);
        row.setReasonCode("VEHICLE_UNKNOWN");
        row.setReasonText("No vehicle matches fleet number 9981");

        publisher.publishIfTerminal(row, NOW);

        verify(outboxEventWriter).publish(eq("supplier.events.v1"), envelopeCaptor.capture());
        Assertions.assertThat(envelopeCaptor.getValue().eventType())
                .isEqualTo(SupplierWorkorderAuthDeniedV1.EVENT_TYPE);
        Assertions.assertThat(envelopeCaptor.getValue().payload())
                .isInstanceOfSatisfying(
                        SupplierWorkorderAuthDeniedV1.class,
                        payload -> Assertions.assertThat(payload.reasonCode()).isEqualTo("VEHICLE_UNKNOWN"));
    }

    @Test
    @DisplayName("an authorization nobody could get an answer for publishes nothing at all")
    void manualReviewIsNeverPublished() {
        // The load-bearing case. MANUAL_REVIEW means the vendor was unreachable or unreadable.
        // Publishing it as a denial would send a shop to argue with a fleet manager about a
        // decision that was never made.
        publisher.publishIfTerminal(row(WorkorderAuthorizationStatus.MANUAL_REVIEW), NOW);

        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("an authorization the vendor has not decided yet publishes nothing")
    void pendingIsNeverPublished() {
        publisher.publishIfTerminal(row(WorkorderAuthorizationStatus.PENDING), NOW);

        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("decisions are keyed on the authorization, so one workorder's answers stay ordered")
    void decisionsAreKeyedOnTheAuthorization() {
        SupplierWorkorderAuthorizationEntity row = row(WorkorderAuthorizationStatus.DENIED);

        publisher.publishIfTerminal(row, NOW);

        verify(outboxEventWriter).publish(any(), envelopeCaptor.capture());
        // A grant overtaking the denial it replaced would authorise work the fleet had refused.
        Assertions.assertThat(envelopeCaptor.getValue().aggregateId())
                .isEqualTo(row.getSupplierWorkorderAuthorizationId());
    }

    private static SupplierWorkorderAuthorizationEntity row(WorkorderAuthorizationStatus status) {
        return SupplierWorkorderAuthorizationEntity.builder()
                .supplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .vendorProfileId(UUID.fromString("019200aa-0000-7000-8000-0000000000b1"))
                .supplierRef("michelin-de")
                .workorderId(UUID.fromString("019200aa-0000-7000-8000-0000000000c1"))
                .status(status)
                .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                .requestedAt(NOW)
                .build();
    }
}
