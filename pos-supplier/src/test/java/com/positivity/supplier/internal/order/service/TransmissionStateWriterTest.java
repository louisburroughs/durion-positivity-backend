package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierOrderConfirmedV1;
import com.positivity.domainevents.supplier.SupplierOrderRejectedV1;
import com.positivity.domainevents.supplier.SupplierOrderReviewRequiredV1;
import com.positivity.domainevents.supplier.SupplierOrderStatusChangedV1;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Transmission state transitions and their events (ADR-0052, #1226/#1318)")
class TransmissionStateWriterTest {

    private static final UUID INTENT_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");
    private static final String DOCUMENT_ID = "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D";

    @Mock
    private SupplierTransmissionIntentRepository intentRepository;

    @Mock
    private SupplierOutboxEventWriter outboxWriter;

    private SupplierTransmissionIntentEntity intent;
    private TransmissionStateWriter writer;

    @BeforeEach
    void setUp() {
        intent = SupplierTransmissionIntentEntity.builder()
                .transmissionIntentId(INTENT_ID)
                .vendorProfileId(UUID.randomUUID())
                .supplierRef("michelin-eu")
                .purchaseOrderId(UUID.randomUUID())
                .intentType(SupplierPurchaseOrder.IntentType.INITIAL)
                .documentId(DOCUMENT_ID)
                .activeIntentKey("claimed")
                .attemptState(TransmissionAttemptState.DISPATCHING)
                .build();
        when(intentRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(intentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        writer = new TransmissionStateWriter(
                intentRepository,
                outboxWriter,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC));
    }

    private static SupplierOrderStatusResult status(SupplierDespatch... despatches) {
        return new SupplierOrderStatusResult(
                DOCUMENT_ID,
                SupplierOrderStatusResult.Status.CONFIRMED,
                "MICH-1",
                null,
                null,
                null,
                List.of(new SupplierOrderStatusResult.Line(
                        "000001",
                        null,
                        "3528709999083",
                        null,
                        null,
                        null,
                        10,
                        LocalDate.of(2026, 8, 10),
                        List.of(new SupplierDeliverySchedule(
                                LocalDate.of(2026, 8, 12), 10, null, 4, null, null, null, List.of(despatches))),
                        null,
                        null)));
    }

    @SuppressWarnings("unchecked")
    private List<DomainEventEnvelope<?>> published() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter, atLeastOnce()).publish(anyString(), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void marksDispatchingOnlyFromPending() {
        // Two instances polling the same queue must not both send. The loser sees a row that is no
        // longer PENDING and stands down.
        intent.setAttemptState(TransmissionAttemptState.DISPATCHING);

        assertThat(writer.markDispatching(INTENT_ID, "corr-1")).isFalse();
    }

    @Test
    void publishesAConfirmationWhenTheVendorAccepts() {
        writer.recordCreateResult(
                INTENT_ID,
                new SupplierOrderResult(SupplierOrderResult.Status.CONFIRMED, "MICH-770412", null, null, List.of()));

        assertThat(intent.getAttemptState()).isEqualTo(TransmissionAttemptState.CONFIRMED);
        assertThat(published())
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.eventType()).isEqualTo(SupplierOrderConfirmedV1.EVENT_TYPE));
    }

    @Test
    void startsFulfilmentPollingAtConfirmationRatherThanEndingIt() {
        // Confirmation is where the delivery story starts: the confirmed dates, despatches and ASN
        // references #1318 carries all arrive after this point.
        writer.recordCreateResult(
                INTENT_ID,
                new SupplierOrderResult(SupplierOrderResult.Status.CONFIRMED, "MICH-770412", null, null, List.of()));

        assertThat(intent.isStatusPollingActive()).isTrue();
    }

    @Test
    void keepsAConfirmedOrdersClaimOnItsTupleForever() {
        // A vendor that accepted an order holds that (profile, intent type, order, revision) tuple
        // for good, so a re-issued command for it is recognised as a repeat rather than becoming a
        // second truckload.
        writer.recordCreateResult(
                INTENT_ID,
                new SupplierOrderResult(SupplierOrderResult.Status.CONFIRMED, "MICH-770412", null, null, List.of()));

        assertThat(intent.getActiveIntentKey()).isEqualTo("claimed");
    }

    @Test
    void releasesTheTupleWhenTheVendorRefusesTheOrder() {
        // A refused order can legitimately be placed again, under a new intent and a new document
        // id.
        writer.recordCreateResult(
                INTENT_ID,
                new SupplierOrderResult(SupplierOrderResult.Status.REJECTED, null, "Buyer unknown", "913", List.of()));

        assertThat(intent.getAttemptState()).isEqualTo(TransmissionAttemptState.REJECTED);
        assertThat(intent.getActiveIntentKey()).isNull();
        assertThat(published())
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.eventType()).isEqualTo(SupplierOrderRejectedV1.EVENT_TYPE));
    }

    @Test
    void publishesTheStateButNoOutcomeWhenATransmissionEntersManualReview() {
        writer.recordManualReview(INTENT_ID, "vendor unreachable after send");

        assertThat(intent.getAttemptState()).isEqualTo(TransmissionAttemptState.MANUAL_REVIEW);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter).publish(anyString(), captor.capture());

        // There is no outcome yet -- that is the entire meaning of the state -- so nothing here
        // says "confirmed" or "rejected", which would assert something nobody has established.
        assertThat(captor.getValue().eventType()).isEqualTo(SupplierOrderReviewRequiredV1.EVENT_TYPE);
        assertThat(captor.getValue().payload()).isInstanceOf(SupplierOrderReviewRequiredV1.class);

        // The state itself is published, which is a different claim and a necessary one: left
        // silent, the ordering domain sees a request indistinguishable from one still in progress,
        // so a buyer waits on goods that are not coming and the only record of why lives in a
        // transmission log they have no reason to open (CAP-320 #1330).
        SupplierOrderReviewRequiredV1 payload =
                (SupplierOrderReviewRequiredV1) captor.getValue().payload();
        assertThat(payload.purchaseOrderId()).isEqualTo(intent.getPurchaseOrderId());
        assertThat(payload.documentId()).isEqualTo(intent.getDocumentId());
        assertThat(payload.detail()).contains("vendor unreachable");
    }

    @Test
    void publishesAStatusChangeTheFirstTimeAVendorAnswers() {
        assertThat(writer.recordStatusObservation(INTENT_ID, status(), 45)).isTrue();

        assertThat(published())
                .singleElement()
                .satisfies(envelope ->
                        assertThat(envelope.eventType()).isEqualTo(SupplierOrderStatusChangedV1.EVENT_TYPE));
    }

    @Test
    void publishesNothingWhenTheVendorRepeatsItself() {
        // A poller asking every few minutes would otherwise republish an unchanged order all day and
        // drown the timeline it exists to inform.
        writer.recordStatusObservation(INTENT_ID, status(), 45);

        assertThat(writer.recordStatusObservation(INTENT_ID, status(), 45)).isFalse();
        verify(outboxWriter, times(1)).publish(anyString(), any());
    }

    @Test
    void publishesAgainWhenANewDespatchAppears() {
        writer.recordStatusObservation(INTENT_ID, status(), 45);

        boolean changed = writer.recordStatusObservation(
                INTENT_ID, status(new SupplierDespatch(LocalDate.of(2026, 8, 11), "ASN-1", null, 6)), 45);

        assertThat(changed).isTrue();
        verify(outboxWriter, times(2)).publish(anyString(), any());
    }

    @Test
    void carriesTheWholeScheduleAndDespatchTreeOntoTheEvent() {
        writer.recordStatusObservation(
                INTENT_ID,
                status(new SupplierDespatch(LocalDate.of(2026, 8, 11), "ASN-2026-08-11-0001", "000001", 6)),
                45);

        SupplierOrderStatusChangedV1 payload =
                (SupplierOrderStatusChangedV1) published().getFirst().payload();
        var line = payload.lines().getFirst();

        assertThat(line.requestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(line.schedules()).singleElement().satisfies(schedule -> {
            assertThat(schedule.deliveryDate()).isEqualTo(LocalDate.of(2026, 8, 12));
            assertThat(schedule.openQuantity()).isEqualTo(4);
            assertThat(schedule.despatches()).singleElement().satisfies(despatch -> {
                assertThat(despatch.despatchDate()).isEqualTo(LocalDate.of(2026, 8, 11));
                // Verbatim: the operator's handle for a vendor query, and the join key if an ASN
                // feed ever appears (ADR-0013/0027).
                assertThat(despatch.despatchAdviceDocumentId()).isEqualTo("ASN-2026-08-11-0001");
                assertThat(despatch.despatchedQuantity()).isEqualTo(6);
            });
        });
    }

    @Test
    void recordsAnOperatorsConfirmationAsManuallySourced() {
        // A human's assertion and a vendor's answer are both confirmations, and a timeline that
        // showed them identically would hide which orders someone vouched for.
        writer.recordResolution(
                INTENT_ID,
                TransmissionAttemptState.CONFIRMED,
                "CONFIRM_WITH_VENDOR_REFERENCE",
                "ops.jo",
                "called the vendor, they hold it",
                "MICH-770412");

        SupplierOrderConfirmedV1 payload =
                (SupplierOrderConfirmedV1) published().getFirst().payload();

        assertThat(payload.source()).isEqualTo(SupplierOrderConfirmedV1.Source.MANUAL_RESOLUTION);
        assertThat(payload.supplierOrderNumber()).isEqualTo("MICH-770412");
        assertThat(intent.getResolvedBy()).isEqualTo("ops.jo");
        assertThat(intent.getResolutionEvidence()).isEqualTo("called the vendor, they hold it");
    }

    @Test
    void distinguishesNotReceivedFromCancelledOnTheTimeline() {
        writer.recordResolution(
                INTENT_ID,
                TransmissionAttemptState.FAILED,
                "MARK_NOT_RECEIVED",
                "ops.jo",
                "vendor has no such order",
                null);

        SupplierOrderRejectedV1 payload =
                (SupplierOrderRejectedV1) published().getFirst().payload();

        assertThat(payload.reason()).isEqualTo(SupplierOrderRejectedV1.Reason.MANUAL_NOT_RECEIVED);
        assertThat(intent.getActiveIntentKey()).isNull();
    }

    @Test
    void stampsAMonotonicSequenceOnEveryEventAboutOneIntent() {
        writer.recordStatusObservation(INTENT_ID, status(), 45);
        writer.recordStatusObservation(
                INTENT_ID, status(new SupplierDespatch(LocalDate.of(2026, 8, 11), "ASN-1", null, 6)), 45);

        assertThat(published())
                .extracting(DomainEventEnvelope::aggregateVersion)
                .containsExactly(1L, 2L);
    }
}
