package com.positivity.order.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusSummary;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderSummaryResponse;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.service.ProcurementAvailabilityService;
import com.positivity.order.internal.service.PurchaseOrderService;
import com.positivity.order.internal.service.PurchaseOrderTransmissionService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #1798: {@code GET /v1/orders/purchase-orders/summary} is the whole-population aggregate the paged
 * list cannot provide. It sits beside {@code /{poId}} and must resolve as its own path, not as an
 * order whose id is the word "summary".
 */
@WebMvcTest(PurchaseOrderController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
@DisplayName("GET /v1/orders/purchase-orders/summary")
class PurchaseOrderSummaryControllerTest extends BaseControllerSliceTest {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

    @MockitoBean
    private PurchaseOrderTransmissionService purchaseOrderTransmissionService;

    @MockitoBean
    private ProcurementAvailabilityService procurementAvailabilityService;

    private static PurchaseOrderSummaryResponse.PurchaseOrderSummaryResponseBuilder empty(
            List<PurchaseOrderStatus> statuses) {
        return PurchaseOrderSummaryResponse.builder()
                .statuses(statuses)
                .unitsOrdered(BigDecimal.ZERO)
                .unitsOpen(BigDecimal.ZERO)
                .unitsReceived(BigDecimal.ZERO)
                .byStatus(List.of());
    }

    @Test
    @DisplayName("answers the aggregate with both filters parsed, and does not treat 'summary' as an order id")
    void answersTheAggregate() throws Exception {
        // Alpha 2026-09-05: nothing approved had been received yet, so ordered and open coincide.
        List<PurchaseOrderStatus> asked = List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.CANCELLED);
        when(purchaseOrderService.summarizePurchaseOrders(eq(VENDOR_ID), eq(asked)))
                .thenReturn(PurchaseOrderSummaryResponse.builder()
                        .vendorId(VENDOR_ID)
                        .statuses(asked)
                        .orderCount(144)
                        .lineCount(600)
                        .unitsOrdered(new BigDecimal("2351"))
                        .unitsOpen(new BigDecimal("2351"))
                        .unitsReceived(BigDecimal.ZERO)
                        .grandTotalMinor(1_000_00L)
                        .openBalanceMinor(1_000_00L)
                        .byStatus(List.of(new PurchaseOrderStatusSummary(
                                PurchaseOrderStatus.APPROVED,
                                144,
                                600,
                                new BigDecimal("2351"),
                                new BigDecimal("2351"),
                                BigDecimal.ZERO,
                                1_000_00L,
                                1_000_00L)))
                        .build());

        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-orders/summary")
                                .param("vendorId", VENDOR_ID.toString())
                                .param("status", "APPROVED", "CANCELLED"),
                        "order:purchase_order:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(144))
                .andExpect(jsonPath("$.unitsOpen").value(2351))
                .andExpect(jsonPath("$.statuses[1]").value("CANCELLED"))
                .andExpect(jsonPath("$.byStatus[0].status").value("APPROVED"));

        verify(purchaseOrderService, never()).getPurchaseOrder(any());
    }

    @Test
    @DisplayName("both filters are optional; the service decides what 'no status' means")
    void filtersAreOptional() throws Exception {
        when(purchaseOrderService.summarizePurchaseOrders(isNull(), isNull()))
                .thenReturn(empty(List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED))
                        .build());

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/summary"), "order:purchase_order:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(0))
                .andExpect(jsonPath("$.statuses[0]").value("APPROVED"))
                .andExpect(jsonPath("$.byStatus").isEmpty());
    }

    @Test
    @DisplayName("a comma-separated status value binds as a list — the facade tool sends one joined value")
    void commaSeparatedStatusesBind() throws Exception {
        List<PurchaseOrderStatus> asked = List.of(PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.CLOSED);
        when(purchaseOrderService.summarizePurchaseOrders(isNull(), eq(asked)))
                .thenReturn(empty(asked).build());

        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-orders/summary").param("status", "DRAFT,CLOSED"),
                        "order:purchase_order:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses[1]").value("CLOSED"));
    }

    @Test
    @DisplayName("needs the purchase-order view authority, like the list it aggregates")
    void needsViewAuthority() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/summary"), "order:purchase_order:create"))
                .andExpect(status().isForbidden());

        verify(purchaseOrderService, never()).summarizePurchaseOrders(any(), any());
    }
}
