package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.service.ProcurementAvailabilityService;
import com.positivity.order.internal.service.PurchaseOrderService;
import com.positivity.order.internal.service.PurchaseOrderTransmissionService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Issue #1694: {@link PurchaseOrderExceptionHandler} no longer has a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)}. {@link PurchaseOrderRequestValidationException}
 * keeps the genuine-client-error contract (400 {@code PURCHASE_ORDER_BAD_REQUEST}, message echoed —
 * unchanged status, since 400 was already correct for this case), while a bare
 * {@code IllegalArgumentException} is no longer caught by this module's advice and falls through
 * to {@code pos-web-common}'s {@code GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 that never echoes the exception's own text.
 *
 * <p>Runs as a {@code @WebMvcTest} slice on {@link com.positivity.order.BaseControllerSliceTest},
 * whose Javadoc records why this module could not be sliced before #1723.
 */
@DisplayName("Purchase-order endpoints answer the errors they document — issue #1694 additions")
@WebMvcTest(PurchaseOrderController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class PurchaseOrderControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b05");

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

    @MockitoBean
    private PurchaseOrderTransmissionService purchaseOrderTransmissionService;

    @MockitoBean
    private ProcurementAvailabilityService procurementAvailabilityService;

    @Test
    @DisplayName("a request validation failure answers 400 with its own message and code")
    void aRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(purchaseOrderService.getPurchaseOrder(any()))
                .thenThrow(new PurchaseOrderRequestValidationException(
                        "quantity is required when documentUom/documentQuantity are absent"));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-orders/{poId}", PO_ID), "order:purchase_order:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("quantity is required when documentUom/documentQuantity are absent"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'unitCostMinor'";
        when(purchaseOrderService.getPurchaseOrder(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(
                        withGatewayAuth(get("/v1/orders/purchase-orders/{poId}", PO_ID), "order:purchase_order:view"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("UnknownPathException");
    }
}
