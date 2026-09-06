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

    @Test
    @DisplayName("answers the aggregate with the filters parsed, and does not treat 'summary' as an order id")
    void answersTheAggregate() throws Exception {
        when(purchaseOrderService.summarizePurchaseOrders(eq(VENDOR_ID), eq(PurchaseOrderStatus.APPROVED)))
                .thenReturn(PurchaseOrderSummaryResponse.builder()
                        .vendorId(VENDOR_ID)
                        .status(PurchaseOrderStatus.APPROVED)
                        .orderCount(144)
                        .lineCount(600)
                        .unitsOrdered(new BigDecimal("7849"))
                        .unitsOpen(new BigDecimal("2351"))
                        .unitsReceived(new BigDecimal("5498"))
                        .grandTotalMinor(1_000_00L)
                        .openBalanceMinor(300_00L)
                        .byStatus(List.of(new PurchaseOrderStatusSummary(
                                PurchaseOrderStatus.APPROVED,
                                144,
                                600,
                                new BigDecimal("7849"),
                                new BigDecimal("2351"),
                                new BigDecimal("5498"),
                                1_000_00L,
                                300_00L)))
                        .build());

        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-orders/summary")
                                .param("vendorId", VENDOR_ID.toString())
                                .param("status", "APPROVED"),
                        "order:purchase_order:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(144))
                .andExpect(jsonPath("$.unitsOpen").value(2351))
                .andExpect(jsonPath("$.unitsOrdered").value(7849))
                .andExpect(jsonPath("$.byStatus[0].status").value("APPROVED"));

        verify(purchaseOrderService, never()).getPurchaseOrder(any());
    }

    @Test
    @DisplayName("both filters are optional")
    void filtersAreOptional() throws Exception {
        when(purchaseOrderService.summarizePurchaseOrders(isNull(), isNull()))
                .thenReturn(PurchaseOrderSummaryResponse.builder()
                        .unitsOrdered(BigDecimal.ZERO)
                        .unitsOpen(BigDecimal.ZERO)
                        .unitsReceived(BigDecimal.ZERO)
                        .byStatus(List.of())
                        .build());

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/summary"), "order:purchase_order:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(0))
                .andExpect(jsonPath("$.byStatus").isEmpty());
    }

    @Test
    @DisplayName("needs the purchase-order view authority, like the list it aggregates")
    void needsViewAuthority() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/summary"), "order:purchase_order:create"))
                .andExpect(status().isForbidden());

        verify(purchaseOrderService, never()).summarizePurchaseOrders(any(), any());
    }
}
