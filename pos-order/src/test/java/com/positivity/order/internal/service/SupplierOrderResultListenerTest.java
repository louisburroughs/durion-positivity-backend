package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Hearing back from the vendor (CAP-320 #1330).
 *
 * <p>Two properties carry most of the weight. Replay must not double-count, because these events
 * are redelivered as a matter of course. And a late status observation must not undo a settled
 * answer — status is polled, so an old reply landing after a new one is ordinary rather than
 * exceptional, and an order that flips back to "in progress" after the vendor confirmed it sends
 * a buyer chasing something that has already been agreed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierOrderResultListener — what the vendor said (#1330)")
class SupplierOrderResultListenerTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");
    private static final UUID INTENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e02");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e03");
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    private SupplierOrderResultListener listener;
    private PurchaseOrderEntity order;

    @BeforeEach
    void setUp() {
        listener = new SupplierOrderResultListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                purchaseOrderRepository,
                transmissionEventRepository);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        order = PurchaseOrderEntity.builder()
                .purchaseOrderId(PO_ID)
                .poNumber("0000001A")
                .transmissionState(TransmissionState.REQUESTED)
                .transmissionCount(1)
                .build();
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));
    }

    private static String confirmed(String eventId, String observedAt) {
        return """
            {"eventId":"%s","eventType":"supplier.order.confirmed","payload":{
              "transmissionIntentId":"%s","purchaseOrderId":"%s","vendorProfileId":"%s",
              "supplierRef":"acme-parts","documentId":"DOC-1","supplierOrderNumber":"V-9911",
              "source":"ORDER_RESPONSE","confirmedAt":"%s","lines":[]}}
            """.formatted(eventId, INTENT_ID, PO_ID, PROFILE_ID, observedAt);
    }

    private static String rejected(String eventId) {
        return """
            {"eventId":"%s","eventType":"supplier.order.rejected","payload":{
              "transmissionIntentId":"%s","purchaseOrderId":"%s","vendorProfileId":"%s",
              "supplierRef":"acme-parts","documentId":"DOC-1","reason":"VENDOR_REJECTED",
              "vendorErrorCode":"E12","vendorReason":"article discontinued",
              "rejectedAt":"2026-08-16T11:00:00Z","lines":[]}}
            """.formatted(eventId, INTENT_ID, PO_ID, PROFILE_ID);
    }

    private static String statusChanged(String eventId, String status, String observedAt, String deliveryDate) {
        return """
            {"eventId":"%s","eventType":"supplier.orderstatus.changed","payload":{
              "transmissionIntentId":"%s","purchaseOrderId":"%s","vendorProfileId":"%s",
              "supplierRef":"acme-parts","documentId":"DOC-1","supplierOrderNumber":"V-9911",
              "status":"%s","vendorReason":null,"observedAt":"%s","lines":[
                {"lineId":"1","customerLineItemNumber":"1","articleEan":"5012345678900",
                 "supplierArticleCode":null,"buyersArticleId":null,"description":"rotor",
                 "orderedQuantity":10,"requestedDeliveryDate":null,
                 "schedules":[{"deliveryDate":%s,"confirmedQuantity":10,"cancelledQuantity":null,
                   "openQuantity":null,"backorderedQuantity":null,"scheduledQuantity":null,
                   "shippedQuantity":null,"despatches":[{"despatchDate":"2026-09-02",
                     "despatchAdviceDocumentId":"ASN-7","despatchAdviceLineId":"1",
                     "despatchedQuantity":10}]}],
                 "vendorErrorCode":null,"vendorErrorText":null}]}}
            """.formatted(eventId, INTENT_ID, PO_ID, PROFILE_ID, status, observedAt, deliveryDate);
    }

    private static String reviewRequired(String eventId) {
        return """
            {"eventId":"%s","eventType":"supplier.order.reviewrequired","payload":{
              "transmissionIntentId":"%s","purchaseOrderId":"%s","vendorProfileId":"%s",
              "supplierRef":"acme-parts","documentId":"DOC-1",
              "detail":"send timed out with no acknowledgement",
              "escalatedAt":"2026-08-16T10:30:00Z"}}
            """.formatted(eventId, INTENT_ID, PO_ID, PROFILE_ID);
    }

    @Test
    @DisplayName("a confirmation records the vendor's own reference on the order")
    void confirmationRecordsTheVendorReference() {
        listener.onSupplierEvent(confirmed("evt-1", "2026-08-16T11:00:00Z"));

        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.CONFIRMED);
        // The vendor's number is what an operator quotes when chasing the order; without it the
        // only shared handle is our own, which the vendor cannot look up.
        assertThat(order.getSupplierOrderNumber()).isEqualTo("V-9911");
        assertThat(order.getVendorDocumentId()).isEqualTo("DOC-1");
        assertThat(order.getTransmissionIntentId()).isEqualTo(INTENT_ID);
    }

    @Test
    @DisplayName("a rejection records why, and does not resend")
    void rejectionRecordsTheReason() {
        listener.onSupplierEvent(rejected("evt-2"));

        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.REJECTED);
        assertThat(order.getTransmissionRejectionReason()).isEqualTo("VENDOR_REJECTED");
        assertThat(order.getTransmissionVendorReason()).isEqualTo("article discontinued");
    }

    @Test
    @DisplayName("a redelivered event changes nothing")
    void replayIsANoOp() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        listener.onSupplierEvent(confirmed("evt-1", "2026-08-16T11:00:00Z"));

        verify(purchaseOrderRepository, never()).save(any());
        verify(transmissionEventRepository, never()).save(any());
        // Not even a second processed-event row: the guard returns before any write.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a status observation older than the applied one joins the timeline but not the state")
    void staleStatusDoesNotMoveTheOrder() {
        order.setTransmissionObservedAt(Instant.parse("2026-08-16T11:00:00Z"));
        order.setTransmissionState(TransmissionState.REQUESTED);

        listener.onSupplierEvent(statusChanged("evt-3", "CONFIRMED", "2026-08-16T09:00:00Z", "\"2026-09-05\""));

        // The observation is real and belongs on the record — it just describes an earlier moment
        // than the one already applied, so it must not move the order's current state.
        verify(transmissionEventRepository).save(any());
        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.REQUESTED);
    }

    @Test
    @DisplayName("a status poll from before a decision does not undo it")
    void statusDoesNotOverturnASettledAnswer() {
        order.setTransmissionState(TransmissionState.CONFIRMED);
        order.setTransmissionObservedAt(Instant.parse("2026-08-16T11:00:00Z"));

        listener.onSupplierEvent(statusChanged("evt-4", "IN_PROGRESS", "2026-08-16T12:30:00Z", "null"));

        // Even though this observation is newer, a vendor that has confirmed has decided; a poll is
        // a weaker statement than a decision, and flipping back would send a buyer chasing an order
        // that has already been agreed.
        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.CONFIRMED);
    }

    @Test
    @DisplayName("the timeline carries the despatch and delivery dates the vendor stated")
    void timelineCarriesTheDates() {
        listener.onSupplierEvent(statusChanged("evt-5", "IN_PROGRESS", "2026-08-16T12:30:00Z", "\"2026-09-05\""));

        ArgumentCaptor<PurchaseOrderTransmissionEvent> captor =
                ArgumentCaptor.forClass(PurchaseOrderTransmissionEvent.class);
        verify(transmissionEventRepository).save(captor.capture());
        // These are the dates a buyer actually wants: not when we asked, but when the vendor says
        // it will ship and arrive.
        assertThat(captor.getValue().getDespatchDate()).hasToString("2026-09-02");
        assertThat(captor.getValue().getEstimatedDeliveryDate()).hasToString("2026-09-05");
        assertThat(captor.getValue().getObservedAt()).isEqualTo(Instant.parse("2026-08-16T12:30:00Z"));
        // Kept apart from observedAt, so an answer that arrived late is visibly late.
        assertThat(captor.getValue().getRecordedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("NOT_FOUND leaves the order where it is rather than reading as a refusal")
    void notFoundIsNotARefusal() {
        listener.onSupplierEvent(statusChanged("evt-6", "NOT_FOUND", "2026-08-16T12:30:00Z", "null"));

        // The vendor not recognising the document may mean the send never landed, or that they
        // have not caught up. Treating it as a refusal invites a re-order for goods already coming.
        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.REQUESTED);
    }

    @Test
    @DisplayName("a stalled transmission is visible on the order, not only in the transmission log")
    void reviewRequiredIsVisibleOnTheOrder() {
        listener.onSupplierEvent(reviewRequired("evt-7"));

        // Without this the buyer sees a request that looks in flight and waits on goods that are
        // not coming, with the only explanation in a log they have no reason to open.
        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.MANUAL_REVIEW);
        assertThat(order.getTransmissionVendorReason()).contains("timed out");
    }

    @Test
    @DisplayName("a late escalation does not drag an answered order back into limbo")
    void reviewRequiredDoesNotOverturnAnAnswer() {
        order.setTransmissionState(TransmissionState.CONFIRMED);

        listener.onSupplierEvent(reviewRequired("evt-8"));

        assertThat(order.getTransmissionState()).isEqualTo(TransmissionState.CONFIRMED);
    }

    @Test
    @DisplayName("transient database trouble is retried, not swallowed")
    void transientFailureIsRethrown() {
        when(purchaseOrderRepository.save(any())).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> listener.onSupplierEvent(confirmed("evt-9", "2026-08-16T11:00:00Z")))
                .isInstanceOf(QueryTimeoutException.class);

        // Marking it processed would leave the buyer looking at an order that says it is still
        // waiting for an answer the vendor has already given, with nothing left to correct it.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a result for an unknown order is skipped rather than stalling the partition")
    void unknownOrderIsSkipped() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

        listener.onSupplierEvent(confirmed("evt-10", "2026-08-16T11:00:00Z"));

        verify(purchaseOrderRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }
}
