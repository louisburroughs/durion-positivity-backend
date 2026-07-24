package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.CreateReturnRequest;
import com.positivity.order.internal.dto.RejectReturnRequest;
import com.positivity.order.internal.dto.ReturnLineRequest;
import com.positivity.order.internal.dto.ReturnOrderResponse;
import com.positivity.order.internal.dto.ReturnableLineResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.ReturnOrderService;
import com.positivity.order.service.model.CreateReturnCommand;
import com.positivity.order.service.model.ReturnLineCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns & refunds endpoints (parity stories F1/F2, spec R5.1–R5.5): create a capped return
 * against a completed order, read per-line returnable quantities, and run the approval workflow.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/returns")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
@Tag(name = "Returns", description = "Returns & refunds against completed orders")
public class ReturnOrderController {

    private final ReturnOrderService returnOrderService;

    @Operation(
            summary = "Create a return against a completed order",
            description = "Idempotency-Key header supported: a replayed key returns the original return. Over-cap "
                    + "requests return 422 listing each line's returnableQty.",
            tags = {"Returns"})
    @PostMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_CREATE + "')")
    @EmitEvent(id = "ORDER_RETURN_CREATE", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> createReturn(
            @Parameter(description = "Client idempotency key; replays return the original return")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreateReturnRequest request) {
        List<ReturnLineCommand> lines = request.getLines().stream()
                .map(ReturnOrderController::toLineCommand)
                .toList();
        ReturnOrderResponse response = ReturnOrderResponse.from(returnOrderService.createReturn(new CreateReturnCommand(
                request.getOriginalOrderId(),
                request.getRefundMethod(),
                request.getReasonCode(),
                lines,
                idempotencyKey)));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get a return by id",
            tags = {"Returns"})
    @GetMapping("/{returnOrderId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_VIEW + "')")
    public ResponseEntity<ReturnOrderResponse> getReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.getReturn(returnOrderId)));
    }

    @Operation(
            summary = "List returns for an original order",
            tags = {"Returns"})
    @GetMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_VIEW + "')")
    public ResponseEntity<List<ReturnOrderResponse>> listReturns(@RequestParam UUID originalOrderId) {
        return ResponseEntity.ok(returnOrderService.listByOriginalOrder(originalOrderId).stream()
                .map(ReturnOrderResponse::from)
                .toList());
    }

    @Operation(
            summary = "Per-line returnable quantities for a completed order",
            tags = {"Returns"})
    @GetMapping("/returnable")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_VIEW + "')")
    public ResponseEntity<List<ReturnableLineResponse>> returnableLines(@RequestParam UUID orderId) {
        return ResponseEntity.ok(returnOrderService.returnableLines(orderId).stream()
                .map(ReturnableLineResponse::from)
                .toList());
    }

    @Operation(
            summary = "Approve a pending return",
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/approve")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_APPROVE + "')")
    @EmitEvent(id = "ORDER_RETURN_APPROVE", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> approveReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.approveReturn(returnOrderId)));
    }

    @Operation(
            summary = "Reject a pending return",
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/reject")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_APPROVE + "')")
    @EmitEvent(id = "ORDER_RETURN_REJECT", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> rejectReturn(
            @PathVariable UUID returnOrderId, @Valid @RequestBody RejectReturnRequest request) {
        return ResponseEntity.ok(
                ReturnOrderResponse.from(returnOrderService.rejectReturn(returnOrderId, request.getReason())));
    }

    @Operation(
            summary = "Run the return orchestration saga (refund + restock signal)",
            description = "From RETURN_REQUESTED: issues the refund and emits order.order.returned. A refund "
                    + "failure parks the return at REFUND_FAILED before any stock movement.",
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/process")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_CREATE + "')")
    @EmitEvent(id = "ORDER_RETURN_PROCESS", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> processReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.processReturn(returnOrderId)));
    }

    @Operation(
            summary = "Retry a return saga after a refund failure",
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/retry")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_CREATE + "')")
    @EmitEvent(id = "ORDER_RETURN_RETRY", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> retryReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.retryReturn(returnOrderId)));
    }

    private static ReturnLineCommand toLineCommand(ReturnLineRequest line) {
        return new ReturnLineCommand(
                line.getOriginalOrderLineId(), line.getReturnQty(), line.getCondition(), line.getSerialNumbers());
    }
}
