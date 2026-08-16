package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReceipt;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderReceiptRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.math.BigDecimal;
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
 * The projection is the supply side of availability-to-promise, so its failure modes are asserted
 * rather than assumed (#1333, ADR-0044 §4).
 *
 * <p>Each test here corresponds to a way the projection can end up disagreeing with the order it
 * mirrors. They matter in one direction more than the other: a projection that is behind
 * over-states open supply, which promises a customer stock that was never ordered or has already
 * been received.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseOrderProjectionListener — ext_purchase_order (#1333)")
class PurchaseOrderProjectionListenerTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04");
    private static final UUID LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtPurchaseOrderRepository orderRepository;

    @Mock
    private ExtPurchaseOrderLineRepository lineRepository;

    @Mock
    private ExtPurchaseOrderReceiptRepository receiptRepository;

    @Mock
    private InventoryFactPublisher inventoryFactPublisher;

    private PurchaseOrderProjectionListener listener;

    @BeforeEach
    void setUp() {
        listener = new PurchaseOrderProjectionListener(
                Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                orderRepository,
                lineRepository,
                receiptRepository,
                inventoryFactPublisher);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(orderRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String event(String eventId, long version, String status, String openQuantity) {
        return """
            {
              "eventId": "%s",
              "eventType": "purchaseorder.updated",
              "aggregateId": "%s",
              "aggregateVersion": %d,
              "payload": {
                "purchaseOrderId": "%s",
                "poNumber": "PO-2026-0001",
                "vendorId": "%s",
                "status": "%s",
                "shipToLocationId": "%s",
                "expectedDeliveryDate": "2026-09-01",
                "currency": "USD",
                "grandTotalMinor": 100000,
                "openBalanceMinor": 40000,
                "occurredAt": "2026-08-15T12:00:00Z",
                "lines": [
                  {
                    "lineId": "%s",
                    "lineNumber": 1,
                    "skuId": "%s",
                    "orderedQuantity": 10,
                    "openQuantity": %s,
                    "unitCostMinor": 5000,
                    "description": "Front brake rotor"
                  }
                ]
              }
            }
            """.formatted(eventId, PO_ID, version, PO_ID, VENDOR_ID, status, SITE_ID, LINE_ID, SKU_ID, openQuantity);
    }

    @Test
    @DisplayName("applies an order and everything availability-to-promise reads from it")
    void appliesTheOrderAndItsLines() {
        listener.onOrderEvent(event("evt-1", 3, "APPROVED", "4"));

        ArgumentCaptor<ExtPurchaseOrderReplica> order = ArgumentCaptor.forClass(ExtPurchaseOrderReplica.class);
        verify(orderRepository).save(order.capture());
        // Status, site and expected date are the three filters the supply query applies; the fourth
        // input, open quantity, lives on the line.
        assertThat(order.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(order.getValue().getShipToLocationId()).isEqualTo(SITE_ID);
        assertThat(order.getValue().getExpectedDeliveryDate()).hasToString("2026-09-01");

        ArgumentCaptor<ExtPurchaseOrderLineReplica> line = ArgumentCaptor.forClass(ExtPurchaseOrderLineReplica.class);
        verify(lineRepository).save(line.capture());
        assertThat(line.getValue().getSkuId()).isEqualTo(SKU_ID);
        assertThat(line.getValue().getOpenQuantity()).isEqualByComparingTo(BigDecimal.valueOf(4));
        // Ordered and open are kept apart: summing ordered would count the six already received.
        assertThat(line.getValue().getOrderedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    @DisplayName("records what it applied, so being behind is a query rather than an inference")
    void recordsTheAppliedVersion() {
        listener.onOrderEvent(event("evt-1", 7, "APPROVED", "4"));

        ArgumentCaptor<ExtPurchaseOrderReceipt> receipt = ArgumentCaptor.forClass(ExtPurchaseOrderReceipt.class);
        verify(receiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getAppliedVersion()).isEqualTo(7L);
        assertThat(receipt.getValue().getPurchaseOrderId()).isEqualTo(PO_ID);
    }

    @Test
    @DisplayName("a redelivered event changes nothing")
    void redeliveryIsANoOp() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        listener.onOrderEvent(event("evt-1", 3, "APPROVED", "4"));

        verify(orderRepository, never()).save(any());
        verify(lineRepository, never()).save(any());
        // Not even a second processed-event row: the guard returns before any write.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a fact older than what is applied cannot resurrect a superseded state")
    void staleFactIsSkipped() {
        ExtPurchaseOrderReplica applied = ExtPurchaseOrderReplica.builder()
                .purchaseOrderId(PO_ID)
                .aggregateVersion(9L)
                .build();
        when(orderRepository.findById(PO_ID)).thenReturn(Optional.of(applied));

        // A slow retry of an old approval arriving after the order was fully received. Applying it
        // would put the order back into open supply and re-promise stock already on the shelf.
        listener.onOrderEvent(event("evt-old", 4, "APPROVED", "10"));

        verify(orderRepository, never()).save(any());
        verify(lineRepository, never()).save(any());
        // Still marked processed: it was handled correctly by being ignored, and redelivering it
        // would only reach the same conclusion.
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("lines are replaced, not merged")
    void linesAreReplacedNotMerged() {
        listener.onOrderEvent(event("evt-1", 3, "APPROVED", "4"));

        // A line the owner deleted must disappear here too. Merging would leave it counted as
        // incoming supply with nothing to reveal it.
        verify(lineRepository).deleteByPurchaseOrderId(PO_ID);
    }

    @Test
    @DisplayName("transient database trouble is retried, not swallowed")
    void transientFailureIsRethrownAndNotMarkedProcessed() {
        when(orderRepository.save(any())).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> listener.onOrderEvent(event("evt-1", 3, "APPROVED", "4")))
                .isInstanceOf(QueryTimeoutException.class);

        // Marking it processed here would drop the order out of the projection permanently, and
        // availability-to-promise would keep quoting a figure short by that order with nothing to
        // show why.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a malformed payload is skipped rather than stalling the partition")
    void malformedPayloadIsSkippedAndMarkedProcessed() {
        listener.onOrderEvent("""
                {"eventId":"evt-bad","eventType":"purchaseorder.updated","payload":{"nonsense":true}}
                """);

        verify(orderRepository, never()).save(any());
        // Marked processed: redelivering it would fail identically, and blocking the partition
        // would stop every other order's facts too.
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("events without an id, and other domains' events, are left alone")
    void ignoresUnusableAndUnrelatedEvents() {
        listener.onOrderEvent("""
                {"eventType":"purchaseorder.updated"}""");
        listener.onOrderEvent("""
                {"eventId":"evt-2","eventType":"order.completed","payload":{}}""");
        listener.onOrderEvent("not json at all");

        verify(orderRepository, never()).save(any());
    }
}
