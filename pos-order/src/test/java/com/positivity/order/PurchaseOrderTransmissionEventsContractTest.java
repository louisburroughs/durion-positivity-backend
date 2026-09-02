package com.positivity.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The purchase-order transmission timeline read (issue #1638, decision #2).
 *
 * <h2>What the ordering test really guards</h2>
 *
 * Status observations are polled, so they arrive out of order as a matter of course. The endpoint
 * promises the vendor's sequence — observedAt ascending — with receipt time breaking ties, and the
 * test data is arranged so that insertion order, generated-id order and the promised order all
 * disagree: an endpoint that leans on any accidental ordering fails here, not in production when
 * the first late poll lands.
 */
@DisplayName("Purchase-order transmission timeline read (#1638)")
class PurchaseOrderTransmissionEventsContractTest extends BaseContractIntegrationTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID EMPTY_PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a02");
    private static final UUID UNKNOWN_PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5aff");
    private static final String VIEW = "order:purchase_order:view";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    @BeforeEach
    void setUp() {
        purchaseOrder(PO_ID, "TL000001");
        purchaseOrder(EMPTY_PO_ID, "TL000002");
    }

    @AfterEach
    void tearDown() {
        transmissionEventRepository.deleteAll();
        purchaseOrderRepository.deleteById(PO_ID);
        purchaseOrderRepository.deleteById(EMPTY_PO_ID);
    }

    @Test
    @DisplayName("events come back in the vendor's sequence, with receipt time breaking a shared observedAt")
    void timelineIsOrderedByVendorClockThenReceipt() throws Exception {
        // Saved in an order that agrees with neither observedAt nor recordedAt, so the generated
        // UUIDv7 ids (which follow insertion time) cannot accidentally produce the right answer.
        // DOC-B and DOC-C share the vendor timestamp; DOC-C was heard first and must come first.
        event("DOC-B", "2026-08-16T11:00:00Z", "2026-08-16T11:05:00Z");
        event("DOC-A", "2026-08-16T10:00:00Z", "2026-08-16T11:30:00Z");
        event("DOC-C", "2026-08-16T11:00:00Z", "2026-08-16T11:01:00Z");

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/{poId}/transmission-events", PO_ID), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].vendorDocumentId").value("DOC-A"))
                .andExpect(jsonPath("$.content[1].vendorDocumentId").value("DOC-C"))
                .andExpect(jsonPath("$.content[2].vendorDocumentId").value("DOC-B"))
                // Both clocks travel to the caller — that is what makes a late arrival visible.
                .andExpect(jsonPath("$.content[0].observedAt").value("2026-08-16T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].recordedAt").value("2026-08-16T11:30:00Z"))
                .andExpect(jsonPath("$.content[0].transmissionEventId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].eventType").value("STATUS_CHANGED"));
    }

    @Test
    @DisplayName("an order never transmitted has an empty timeline, not an error")
    void untransmittedOrderHasEmptyTimeline() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-orders/{poId}/transmission-events", EMPTY_PO_ID), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("an unknown order is a 404 carrying the error envelope")
    void unknownOrderIsNotFound() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-orders/{poId}/transmission-events", UNKNOWN_PO_ID), VIEW))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("the timeline requires the purchase-order view authority")
    void timelineRequiresViewAuthority() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-orders/{poId}/transmission-events", PO_ID),
                        "order:purchase_order:create"))
                .andExpect(status().isForbidden());
    }

    private void purchaseOrder(UUID poId, String poNumber) {
        purchaseOrderRepository.save(PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .vendorId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b01"))
                .poNumber(poNumber)
                .status(PurchaseOrderStatus.APPROVED)
                .currency("USD")
                .subtotalMinor(50_000L)
                .taxMinor(0L)
                .grandTotalMinor(50_000L)
                .build());
    }

    private void event(String vendorDocumentId, String observedAt, String recordedAt) {
        transmissionEventRepository.save(PurchaseOrderTransmissionEvent.builder()
                .purchaseOrderId(PO_ID)
                .transmissionIntentId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5c01"))
                .eventType("STATUS_CHANGED")
                .status("IN_PROGRESS")
                .vendorDocumentId(vendorDocumentId)
                .observedAt(Instant.parse(observedAt))
                .recordedAt(Instant.parse(recordedAt))
                .build());
    }
}
