package com.positivity.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderNotTransmittableException;
import com.positivity.order.internal.service.PurchaseOrderService;
import com.positivity.order.internal.service.PurchaseOrderTransmissionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The purchase-order endpoints return the errors they advertise (CAP-320 #1330).
 *
 * <h2>Why this test exists</h2>
 *
 * The endpoints have documented 404 and 409 responses since the aggregate moved here (#1334), and
 * returned neither. pos-order scopes its exception advices per controller and the purchase-order
 * controller arrived without one, so every one of these failures fell through to the default
 * handler and came back as a 500 with a body no client could parse.
 *
 * <p>Nothing caught it because the service-level tests assert the exception is thrown, which it
 * was — the gap was entirely between the exception and the wire. So this test works the other way
 * round: it drives the HTTP surface and asserts the status and the envelope a caller was promised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Purchase-order endpoints return the errors they document (#1330)")
class PurchaseOrderErrorContractTest extends BaseContractIntegrationTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4f01");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

    @MockitoBean
    private PurchaseOrderTransmissionService purchaseOrderTransmissionService;

    @Test
    @DisplayName("an unknown order is a 404 carrying the error envelope, not a 500")
    void unknownOrderIsNotFound() throws Exception {
        when(purchaseOrderService.getPurchaseOrder(any())).thenThrow(new PurchaseOrderNotFoundException(PO_ID));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/{poId}", PO_ID), "order:purchase_order:view"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an order already in flight is a 422 naming the obstacle")
    void inFlightTransmissionIsUnprocessable() throws Exception {
        doThrow(PurchaseOrderNotTransmittableException.alreadyInFlight())
                .when(purchaseOrderTransmissionService)
                .requestTransmission(any(), any());

        // The specific code matters more than the status: a caller that cannot tell "already sent"
        // from "no supplier" cannot act on either, and both are things somebody must go and fix.
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/purchase-orders/{poId}/transmit", PO_ID), "order:purchase_order:transmit"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSMISSION_IN_FLIGHT"));
    }

    @Test
    @DisplayName("an unidentifiable article is a 422 naming the lines to fix")
    void unidentifiableArticleIsUnprocessable() throws Exception {
        doThrow(PurchaseOrderNotTransmittableException.articlesNotIdentifiable(List.of(2, 5)))
                .when(purchaseOrderTransmissionService)
                .requestTransmission(any(), any());

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/purchase-orders/{poId}/transmit", PO_ID), "order:purchase_order:transmit"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_IDENTIFIABLE"))
                // The buyer has to know which lines, or the message tells them nothing actionable.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("[2, 5]")));
    }

    @Test
    @DisplayName("a lifecycle refusal is a 409, as the approve and cancel endpoints document")
    void invalidLifecycleTransitionIsConflict() throws Exception {
        when(purchaseOrderService.cancelPurchaseOrder(any(), any()))
                .thenThrow(new IllegalStateException("Cannot cancel a fully received or closed purchase order"));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/purchase-orders/{poId}/cancel", PO_ID), "order:purchase_order:approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_INVALID_STATE"));
    }

    @Test
    @DisplayName("a transmit request without the transmit authority is refused")
    void transmitRequiresItsOwnAuthority() throws Exception {
        // Approving commits the spend; transmitting puts the order in front of a supplier who will
        // act on it. A shop may well grant one and not the other.
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/purchase-orders/{poId}/transmit", PO_ID), "order:purchase_order:approve"))
                .andExpect(status().isForbidden());
    }
}
