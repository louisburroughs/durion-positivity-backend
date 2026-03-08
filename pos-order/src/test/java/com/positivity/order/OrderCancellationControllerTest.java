package com.positivity.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.OrderCancellationService;
import com.positivity.order.service.model.CancellationResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract tests for
 * {@link com.positivity.order.internal.controller.OrderCancellationController}
 * covering Story #19 acceptance criteria.
 *
 * <p>
 * ADR-0017 compliance: 201 for cancel initiation, 409 for invalid state,
 * 404 for not found, 403 for forbidden.
 *
 * <p>
 * Issue: #19
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("OrderCancellationController HTTP Contract Tests — Issue #19")
class OrderCancellationControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderCancellationService orderCancellationService;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String VALID_CANCEL_BODY = """
            {
              "cancellationReason": "Customer requested cancellation",
              "workOrderId": null,
              "paymentId": null,
              "idempotencyKey": "idem-key-001"
            }
            """;

    // ── Issue #19: CC-C01 authorized ──────────────────────────────────────────

    /** ADR-0017: cancel initiation returns 201. */
    @Test
    @DisplayName("CC-C01: POST /{orderId}/cancel authorized ORDER_CANCEL → 201 Created")
    void cancelOrder_authorized_returns201() throws Exception {
        // Arrange
        when(orderCancellationService.cancelOrder(eq(ORDER_ID), any()))
                .thenReturn(new CancellationResult(
                        ORDER_ID,
                        "CANCELLED",
                        "Order cancelled successfully",
                        "test-idempotency-key"));

        // Act & Assert
        mockMvc.perform(withGatewayAuth(
                post("/v1/orders/carts/{orderId}/cancel", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CANCEL_BODY),
                OrderPermissions.ORDER_CANCEL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancellationIdempotencyKey").value("test-idempotency-key"));
    }

    // ── Issue #19: CC-C02 forbidden ───────────────────────────────────────────

    /**
     * ADR-0017: missing permission returns 403.
     * Service throws AccessDeniedException to simulate authorization failure,
     * matching the pattern established in PriceOverrideControllerTest.
     */
    @Test
    @DisplayName("CC-C02: POST /{orderId}/cancel, service throws AccessDeniedException → 403 Forbidden with ORDER_FORBIDDEN code")
    void cancelOrder_forbidden_returns403() throws Exception {
        // Arrange
        when(orderCancellationService.cancelOrder(eq(ORDER_ID), any()))
                .thenThrow(new AccessDeniedException("Forbidden: missing ORDER_CANCEL permission"));

        // Act & Assert — expects 403
        mockMvc.perform(withGatewayAuth(
                post("/v1/orders/carts/{orderId}/cancel", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CANCEL_BODY),
                OrderPermissions.ORDER_CANCEL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }

    // ── Issue #19: CC-C03 validation ──────────────────────────────────────────

    /**
     * ADR-0017: blank cancellationReason triggers @NotBlank validation → 400.
     */
    @Test
    @DisplayName("CC-C03: POST /{orderId}/cancel with blank cancellationReason → 400 Bad Request")
    void cancelOrder_missingReason_returns400() throws Exception {
        String bodyWithBlankReason = """
                {
                  "cancellationReason": "",
                  "workOrderId": null,
                  "paymentId": null,
                  "idempotencyKey": "idem-key-001"
                }
                """;

        // Act & Assert — expects 400 from @NotBlank validation
        mockMvc.perform(withGatewayAuth(
                post("/v1/orders/carts/{orderId}/cancel", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithBlankReason),
                OrderPermissions.ORDER_CANCEL))
                .andExpect(status().isBadRequest());
    }

    // ── Issue #19: CC-C04 retry authorized ───────────────────────────────────

    /** ADR-0017: successful retry returns 200. */
    @Test
    @DisplayName("CC-C04: POST /{orderId}/cancel/retry authorized ORDER_CANCEL → 200 OK")
    void retryCancellation_authorized_returns200() throws Exception {
        // Arrange
        when(orderCancellationService.retryCancellation(eq(ORDER_ID), any()))
                .thenReturn(new CancellationResult(
                        ORDER_ID,
                        "CANCELLED",
                        "Order cancellation completed on retry",
                        "test-idempotency-key"));

        // Act & Assert
        mockMvc.perform(withGatewayAuth(
                post("/v1/orders/carts/{orderId}/cancel/retry", ORDER_ID)
                        .param("idempotencyKey", "idem-key-001"),
                OrderPermissions.ORDER_CANCEL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationIdempotencyKey").value("test-idempotency-key"));
    }

    // ── Issue #19: CC-C05 invalid cancellation state → 409 Conflict ────────────
    @Test
    @DisplayName("CC-C05: POST /{orderId}/cancel when order not cancellable → 409 Conflict")
    void cancelOrder_invalidState_returns409() throws Exception {
        when(orderCancellationService.cancelOrder(eq(ORDER_ID), any()))
                .thenThrow(new IllegalStateException("Order cannot be cancelled in current state: COMPLETED"));

        mockMvc.perform(withGatewayAuth(
                post("/v1/orders/carts/{orderId}/cancel", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CANCEL_BODY),
                OrderPermissions.ORDER_CANCEL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CANCELLATION_INVALID"))
                .andExpect(jsonPath("$.status").value(409));
    }

    // ── Issue #19: CC-C06 order not found → 404 ────────────────────────────────
    @Test
    @DisplayName("CC-C06: POST /{orderId}/cancel when order not found → 404 Not Found")
    void cancelOrder_orderNotFound_returns404() throws Exception {
        when(orderCancellationService.cancelOrder(eq(ORDER_ID), any()))
                .thenThrow(new SalesOrderNotFoundException(ORDER_ID));

        mockMvc.perform(withGatewayAuth(
                post("/v1/orders/carts/{orderId}/cancel", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CANCEL_BODY),
                OrderPermissions.ORDER_CANCEL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }
}
