package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.service.SalesOrderService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Issue #1694: {@link SalesOrderExceptionHandler} no longer has a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)}. {@link SalesOrderRequestValidationException}
 * keeps the genuine-client-error contract (now 400, not the former 422 — request-shape validation
 * per ADR-0017: unsupported tenderType, missing locationId, an unrecognised status/sourceType
 * filter, ...), while a bare {@code IllegalArgumentException} is no longer caught by this module's
 * advice and falls through to {@code pos-web-common}'s {@code GlobalApiExceptionHandler}, which
 * answers a generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>Uses the module's full-context integration pattern ({@link BaseContractIntegrationTest}) —
 * see {@link RegisterSessionControllerErrorHandlingTest} for why a {@code @WebMvcTest} slice does
 * not work here.
 */
@DisplayName("Sales-order endpoints answer the errors they document (#1694)")
@WebMvcTest(SalesOrderController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class SalesOrderControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b04");

    @MockitoBean
    private SalesOrderService salesOrderService;

    @Test
    @DisplayName("a request validation failure answers 400 with its own message and code")
    void aRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(salesOrderService.getOrder(any()))
                .thenThrow(new SalesOrderRequestValidationException("Unsupported tenderType: CRYPTO"));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/carts/{orderId}", ORDER_ID), "order:order:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Unsupported tenderType: CRYPTO"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'tenderType'";
        when(salesOrderService.getOrder(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withGatewayAuth(get("/v1/orders/carts/{orderId}", ORDER_ID), "order:order:view"))
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
