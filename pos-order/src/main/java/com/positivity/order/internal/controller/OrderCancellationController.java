package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.CancelOrderRequest;
import com.positivity.order.internal.dto.CancellationResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.OrderCancellationService;
import com.positivity.order.service.model.CancelOrderCommand;
import com.positivity.order.service.model.CancellationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Order Cancellation", description = "Cancel orders and retry failed cancellations")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {OrderPermissions.ORDER_CANCEL})
@RequestMapping("/v1/orders/carts")
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationController {

    private final OrderCancellationService orderCancellationService;

    @Operation(
            operationId = "cancelOrder",
            summary = "Cancel a Sales Order",
            description = """
                    Runs the order cancellation saga: transitions the order through CANCEL_REQUESTED, cancels a \
                    linked workorder at pos-workexec when workOrderId is supplied, reverses every net-settled \
                    payment as refunds through pos-invoice, and finishes at CANCELLED, publishing an \
                    order-cancelled fact.
                    Use this tool for DRAFT or QUOTED orders and to re-drive CANCEL_FAILED_WORKEXEC or \
                    CANCEL_FAILED_BILLING orders from the start; do not use voidOrder, which is the terminal void \
                    for an unsettled PENDING_PAYMENT order, and do not use retryOrderCancellation, which re-runs \
                    only the billing leg from CANCEL_FAILED_BILLING.
                    Preconditions: the order must be DRAFT, QUOTED, CANCEL_FAILED_WORKEXEC or \
                    CANCEL_FAILED_BILLING, and settled payments require an invoice reference to refund against.
                    Required inputs: cancellationReason; workOrderId is optional and triggers the \
                    workorder-cancellation leg, and idempotencyKey is optional (one is generated when absent) — \
                    replaying it against an already CANCELLED order returns the original result.
                    Emits an ORDER_CART_CANCEL_REQUEST event; refunds are idempotent per payment intent at \
                    pos-invoice, and a failed leg parks the order at CANCEL_FAILED_WORKEXEC or \
                    CANCEL_FAILED_BILLING.
                    Returns 201 when the cancellation completes (or already had for the replayed key), 404 when \
                    the order does not exist, and 409 when the current status is not cancellable or a workorder or \
                    payment-reversal leg fails.
                    """,
            tags = {"Order Cancellation"})
    @ApiResponse(responseCode = "201", description = "Cancellation initiated")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @ApiResponse(responseCode = "409", description = "Order cannot be cancelled in current state")
    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CANCEL + "')")
    @EmitEvent(id = "ORDER_CART_CANCEL_REQUEST", apiVersion = "1")
    public ResponseEntity<CancellationResponse> cancelOrder(
            @PathVariable UUID orderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cancellation context: the business reason plus optional workorder"
                                    + " linkage and replay key.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Cancel with workorder", value = """
                                                                    {"cancellationReason":"Customer changed their mind",
                                                                     "workOrderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
                                                                     "idempotencyKey":"cancel-req-abc123"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CancelOrderRequest request) {
        CancelOrderCommand command = new CancelOrderCommand(
                request.getCancellationReason(), request.getWorkOrderId(), request.getIdempotencyKey());
        CancellationResult result = orderCancellationService.cancelOrder(orderId, command);
        CancellationResponse response = new CancellationResponse();
        response.setOrderId(result.orderId().toString());
        response.setStatus(result.status());
        response.setMessage(result.message());
        response.setCancellationIdempotencyKey(result.cancellationIdempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            operationId = "retryOrderCancellation",
            summary = "Retry a Failed Cancellation",
            description = """
                    Retries the billing leg of a failed order cancellation, re-running the settled-payment \
                    reversals and completing the transition to CANCELLED.
                    Use this tool after a cancellation parked at CANCEL_FAILED_BILLING; do not use cancelOrder, \
                    which starts the saga from the beginning and re-checks the workorder leg as well.
                    Preconditions: the order must be in CANCEL_FAILED_BILLING.
                    Required inputs: orderId (UUID) as a path parameter and idempotencyKey as a query parameter — \
                    the original cancellation key, from which per-intent refund idempotency at pos-invoice is \
                    derived.
                    Emits an ORDER_CART_CANCEL_RETRY event; a retry that fails again transitions the order to \
                    CANCEL_REQUIRES_MANUAL_REVIEW and publishes a review-required fact instead of looping.
                    Returns 200 when the cancellation completes on retry, 404 when the order does not exist, and \
                    409 when the order is not in CANCEL_FAILED_BILLING or the reversal fails again.
                    """,
            tags = {"Order Cancellation"})
    @ApiResponse(responseCode = "200", description = "Retry accepted")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @ApiResponse(responseCode = "409", description = "Order not in retryable state")
    @PostMapping("/{orderId}/cancel/retry")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CANCEL + "')")
    @EmitEvent(id = "ORDER_CART_CANCEL_RETRY", apiVersion = "1")
    public ResponseEntity<CancellationResponse> retryCancellation(
            @PathVariable UUID orderId, @RequestParam String idempotencyKey) {
        CancellationResult result = orderCancellationService.retryCancellation(orderId, idempotencyKey);
        CancellationResponse response = new CancellationResponse();
        response.setOrderId(result.orderId().toString());
        response.setStatus(result.status());
        response.setMessage(result.message());
        response.setCancellationIdempotencyKey(result.cancellationIdempotencyKey());
        return ResponseEntity.ok(response);
    }
}
