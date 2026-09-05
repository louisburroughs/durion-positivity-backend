package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.CreateReturnRequest;
import com.positivity.order.internal.dto.RejectReturnRequest;
import com.positivity.order.internal.dto.ReturnLineRequest;
import com.positivity.order.internal.dto.ReturnOrderResponse;
import com.positivity.order.internal.dto.ReturnableLineResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.ReturnOrderService;
import com.positivity.order.internal.service.model.CreateReturnCommand;
import com.positivity.order.internal.service.model.ReturnLineCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
 * Returns & refunds endpoints (parity stories F1/F2, spec R5.1–R5.5): create a
 * capped return
 * against a completed order, read per-line returnable quantities, and run the
 * approval workflow.
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
            operationId = "createReturn",
            summary = "Create a Return Against a Completed Order",
            description = """
                    Creates a return order against one COMPLETED sales order, capping each line at its un-refunded \
                    remainder (row-locked so concurrent returns serialize) and computing a pro-rata, tax-included \
                    refund per line.
                    Use this tool to take goods back after a completed sale; do not use cancelOrder or voidOrder, \
                    which abandon an order before completion, and check listReturnableLines first to see each \
                    line's remaining returnable quantity.
                    Preconditions: the original order must be COMPLETED, every line must be returnable (workorder \
                    consumed lines without an imported returnable flag are not), and each sold line may appear at \
                    most once in the request.
                    Required inputs: originalOrderId (UUID), refundMethod (ORIGINAL_TENDER, STORE_CREDIT or \
                    ON_ACCOUNT_CREDIT), and at least one line with originalOrderLineId, a positive returnQty, and \
                    condition (RESTOCK, SCRAP or WARRANTY); reasonCode, per-line serialNumbers, and the \
                    Idempotency-Key header (replays return the original return) are optional.
                    Emits an ORDER_RETURN_CREATE event; a refund total above the approval threshold (default \
                    250.00, configurable via pos.order.return.approval-threshold) parks the return at \
                    PENDING_APPROVAL, otherwise it starts at RETURN_REQUESTED ready for processReturn.
                    Returns 201 on creation (idempotent replays included), 400 when the request has no lines, a \
                    line reference is duplicated or unknown, returnQty is not positive, or refundMethod/condition \
                    is unrecognised, 404 when the original order does not exist, 409 when the original order is \
                    not COMPLETED, and 422 when a line exceeds its returnable remainder (each offending line's \
                    returnableQty is listed in fieldErrors), a line is not returnable, or a WARRANTY-condition \
                    line must route to pos-warranty.
                    """,
            tags = {"Returns"})
    @PostMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_CREATE + "')")
    @EmitEvent(id = "ORDER_RETURN_CREATE", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> createReturn(
            @Parameter(description = "Client idempotency key; replays return the original return")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The return: source order, refund method, and the lines coming back.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Single-line restock return", value = """
                                                                    {"originalOrderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "refundMethod":"ORIGINAL_TENDER",
                                                                     "reasonCode":"DEFECTIVE",
                                                                     "lines":[{"originalOrderLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                               "returnQty":1,
                                                                               "condition":"RESTOCK"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateReturnRequest request) {
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
            operationId = "getReturn",
            summary = "Get a Return by Id",
            description = """
                    Returns a return order with its status, refund method and total, failure reason when present, \
                    and per-line quantities and refunds.
                    Use this tool when the return order id is already known; use listReturns instead to find every \
                    return created from an original order.
                    Preconditions: the return order must exist.
                    Required inputs: returnOrderId (UUID) as a path parameter; there is no request body.
                    Emits an ORDER_RETURN_GET audit event; no state changes — this is a read-only projection.
                    Returns 404 when no return order exists for the supplied id.
                    """,
            tags = {"Returns"})
    @GetMapping("/{returnOrderId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_VIEW + "')")
    @EmitEvent(id = "ORDER_RETURN_GET", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> getReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.getReturn(returnOrderId)));
    }

    @Operation(
            operationId = "listReturns",
            summary = "List Returns for an Original Order",
            description = """
                    Lists every return order created from one original sales order, newest first.
                    Use this tool to review a sale's return history; use getReturn instead when the return order \
                    id is known, or listReturnableLines to see what can still come back.
                    Preconditions: none — an original order with no returns simply yields an empty list, and the \
                    original order's existence is not checked.
                    Required inputs: originalOrderId (UUID) as a query parameter; there is no request body.
                    Emits an ORDER_RETURN_LIST audit event; no state changes — this is a read-only projection.
                    Returns 200 with a possibly empty list; there are no business error conditions beyond \
                    authorization.
                    """,
            tags = {"Returns"})
    @GetMapping
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_VIEW + "')")
    @EmitEvent(id = "ORDER_RETURN_LIST", apiVersion = "1")
    public ResponseEntity<List<ReturnOrderResponse>> listReturns(@RequestParam UUID originalOrderId) {
        return ResponseEntity.ok(returnOrderService.listByOriginalOrder(originalOrderId).stream()
                .map(ReturnOrderResponse::from)
                .toList());
    }

    @Operation(
            operationId = "listReturnableLines",
            summary = "List Returnable Lines for an Order",
            description = """
                    Returns each line of a COMPLETED order with its sold quantity, already-returned quantity, \
                    remaining returnable quantity, and returnable flag.
                    Use this tool before createReturn to size a valid return; do not use listReturns, which lists \
                    return orders already created rather than remaining capacity.
                    Preconditions: the order must exist and be COMPLETED; workorder-consumed lines without an \
                    imported returnable flag report zero returnable quantity.
                    Required inputs: orderId (UUID) as a query parameter; there is no request body.
                    Emits an ORDER_RETURN_RETURNABLE audit event; no state changes — this is a read-only \
                    projection.
                    Returns 404 when the order does not exist, and 409 when the order is not COMPLETED.
                    """,
            tags = {"Returns"})
    @GetMapping("/returnable")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_VIEW + "')")
    @EmitEvent(id = "ORDER_RETURN_RETURNABLE", apiVersion = "1")
    public ResponseEntity<List<ReturnableLineResponse>> returnableLines(@RequestParam UUID orderId) {
        return ResponseEntity.ok(returnOrderService.returnableLines(orderId).stream()
                .map(ReturnableLineResponse::from)
                .toList());
    }

    @Operation(
            operationId = "approveReturn",
            summary = "Approve a Pending Return",
            description = """
                    Approves a PENDING_APPROVAL return, stamping the approver and moving it to RETURN_REQUESTED so \
                    the refund saga can run.
                    Use this tool for returns whose refund total exceeded the approval threshold; do not use \
                    processReturn, which executes the refund and only accepts a return already in \
                    RETURN_REQUESTED.
                    Preconditions: the return order must exist and be in PENDING_APPROVAL.
                    Required inputs: returnOrderId (UUID) as a path parameter; there is no request body — the \
                    approver is taken from the security context.
                    Emits an ORDER_RETURN_APPROVE event; no refund or stock movement happens yet.
                    Returns 404 when the return does not exist, and 409 when the return is not in \
                    PENDING_APPROVAL.
                    """,
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/approve")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_APPROVE + "')")
    @EmitEvent(id = "ORDER_RETURN_APPROVE", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> approveReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.approveReturn(returnOrderId)));
    }

    @Operation(
            operationId = "rejectReturn",
            summary = "Reject a Pending Return",
            description = """
                    Rejects a PENDING_APPROVAL return with a recorded reason, moving it to the terminal REJECTED \
                    state and releasing its quantities back to the per-line returnable cap.
                    Use this tool to decline an over-threshold return; do not use approveReturn, which releases it \
                    into the refund saga instead.
                    Preconditions: the return order must exist and be in PENDING_APPROVAL.
                    Required inputs: returnOrderId (UUID) as a path parameter and reason in the body.
                    Emits an ORDER_RETURN_REJECT event; no refund is issued and no stock moves.
                    Returns 404 when the return does not exist, and 409 when the return is not in \
                    PENDING_APPROVAL.
                    """,
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/reject")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_APPROVE + "')")
    @EmitEvent(id = "ORDER_RETURN_REJECT", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> rejectReturn(
            @PathVariable UUID returnOrderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The business reason the return is being declined.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Rejection",
                                                            value =
                                                                    "{\"reason\":\"Refund exceeds policy for this item\"}")))
                    @Valid
                    @RequestBody
                    RejectReturnRequest request) {
        return ResponseEntity.ok(
                ReturnOrderResponse.from(returnOrderService.rejectReturn(returnOrderId, request.getReason())));
    }

    @Operation(
            operationId = "processReturn",
            summary = "Run the Return Refund Saga",
            description = """
                    Runs the return orchestration saga from RETURN_REQUESTED: issues the refund (ORIGINAL_TENDER \
                    reverses the original order's settled payments through pos-invoice up to the refund total; \
                    STORE_CREDIT and ON_ACCOUNT_CREDIT record the credit intent and require a customer on the \
                    return), then completes the return.
                    Use this tool to execute an approved or under-threshold return; do not use retryReturn, which \
                    only re-drives a return already parked at REFUND_FAILED.
                    Preconditions: the return must be in RETURN_REQUESTED (an already COMPLETED return is an \
                    idempotent no-op), and an ORIGINAL_TENDER refund needs an invoice and sufficient settled \
                    tender on the original order.
                    Required inputs: returnOrderId (UUID) as a path parameter; there is no request body.
                    Emits an ORDER_RETURN_PROCESS event and publishes an order.order.returned fact after \
                    completion, which pos-inventory consumes to restock RESTOCK-condition lines (SCRAP lines are \
                    skipped); a refund failure parks the return at REFUND_FAILED before any stock signal.
                    Returns 200 when the saga completes (or the return was already COMPLETED), 404 when the return \
                    does not exist, 409 when the status is not RETURN_REQUESTED, 422 when the refund is refused on \
                    its own terms (no invoice to refund against, insufficient settled tender, or a credit refund \
                    with no customer on the return), and 500 when the refund leg itself fails downstream.
                    """,
            tags = {"Returns"})
    @PostMapping("/{returnOrderId}/process")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_RETURN_CREATE + "')")
    @EmitEvent(id = "ORDER_RETURN_PROCESS", apiVersion = "1")
    public ResponseEntity<ReturnOrderResponse> processReturn(@PathVariable UUID returnOrderId) {
        return ResponseEntity.ok(ReturnOrderResponse.from(returnOrderService.processReturn(returnOrderId)));
    }

    @Operation(
            operationId = "retryReturn",
            summary = "Retry a Failed Return Refund",
            description = """
                    Re-runs the return refund saga for a return parked at REFUND_FAILED, using the same per-intent \
                    idempotency so already-reversed payments are never refunded twice.
                    Use this tool after fixing the cause of a refund failure; do not use processReturn, which only \
                    accepts a return in RETURN_REQUESTED.
                    Preconditions: the return must be in REFUND_FAILED; an already COMPLETED return is an \
                    idempotent no-op.
                    Required inputs: returnOrderId (UUID) as a path parameter; there is no request body.
                    Emits an ORDER_RETURN_RETRY event and, on success, publishes the order.order.returned fact \
                    that drives the pos-inventory restock of RESTOCK lines.
                    Returns 200 when the retry completes (or the return was already COMPLETED), 404 when the \
                    return does not exist, 409 when the status is not REFUND_FAILED, 422 when the refund is \
                    refused on its own terms, and 500 when the refund leg fails downstream again.
                    """,
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
