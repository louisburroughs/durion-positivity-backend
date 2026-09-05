package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.service.OrderCancellationService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Issue #1694: {@link OrderCancellationExceptionHandler} no longer has a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)} — nothing reachable through
 * {@link OrderCancellationController} ever threw one (only {@link SalesOrderNotFoundException} and
 * {@code IllegalStateException} are), so it was removed outright rather than replaced. A genuine
 * {@link SalesOrderNotFoundException} keeps its documented 404, while a bare
 * {@code IllegalArgumentException} now falls through to {@code pos-web-common}'s
 * {@code GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 * exception's own text.
 *
 * <p>Uses the module's full-context integration pattern ({@link BaseContractIntegrationTest}) —
 * see {@link RegisterSessionControllerErrorHandlingTest} for why a {@code @WebMvcTest} slice does
 * not work here.
 */
@DisplayName("Order-cancellation endpoints answer the errors they document (#1694)")
@WebMvcTest(OrderCancellationController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class OrderCancellationControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02");

    @MockitoBean
    private OrderCancellationService orderCancellationService;

    @Test
    @DisplayName("a missing order answers 404 with its own message and code")
    void aMissingOrderAnswers404WithItsOwnMessageAndCode() throws Exception {
        when(orderCancellationService.retryCancellation(any(), any()))
                .thenThrow(new SalesOrderNotFoundException(ORDER_ID));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/carts/{orderId}/cancel/retry", ORDER_ID)
                                .param("idempotencyKey", "retry-1"),
                        "order:order:cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "UUID.fromString: invalid UUID string in stored cancellationIdempotencyKey";
        when(orderCancellationService.retryCancellation(any(), any()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/carts/{orderId}/cancel/retry", ORDER_ID)
                                .param("idempotencyKey", "retry-1"),
                        "order:order:cancel"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("cancellationIdempotencyKey");
    }
}
