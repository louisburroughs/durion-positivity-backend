package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.CancelOrderRequest;
import com.positivity.order.internal.dto.CancellationResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.OrderCancellationService;
import com.positivity.order.service.model.CancelOrderCommand;
import com.positivity.order.service.model.CancellationResult;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/v1/orders/carts")
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationController {

    private final OrderCancellationService orderCancellationService;

    @Operation(summary = "Cancel order")
    @ApiResponse(responseCode = "201", description = "Cancellation initiated")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @ApiResponse(responseCode = "409", description = "Order cannot be cancelled in current state")
    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CANCEL + "')")
    @EmitEvent(id = "ORDER_CART_CANCEL_REQUEST", apiVersion = "1")
    public ResponseEntity<CancellationResponse> cancelOrder(
            @PathVariable UUID orderId, @Valid @RequestBody CancelOrderRequest request) {
        CancelOrderCommand command = new CancelOrderCommand(
                request.getCancellationReason(),
                request.getWorkOrderId(),
                request.getPaymentId(),
                request.getIdempotencyKey());
        CancellationResult result = orderCancellationService.cancelOrder(orderId, command);
        CancellationResponse response = new CancellationResponse();
        response.setOrderId(result.orderId().toString());
        response.setStatus(result.status());
        response.setMessage(result.message());
        response.setCancellationIdempotencyKey(result.cancellationIdempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Retry failed cancellation")
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
