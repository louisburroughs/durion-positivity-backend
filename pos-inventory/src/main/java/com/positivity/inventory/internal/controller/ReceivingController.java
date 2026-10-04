package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.CrossDockWorkorderSearchResultDto;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.receiving.service.ReceivingService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

@RestController
@RequestMapping("/v1/inventory/receiving")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Receiving", description = "Receiving session creation, item receiving, and cross-dock execution endpoints")
public class ReceivingController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final String NO_CURRENT_USER = "No current user";
    private final ReceivingService receivingService;

    @PostMapping("/sessions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RECEIVING_CREATE + "')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createReceivingSession",
            summary = "Create Receiving Session",
            description = """
                    Creates an OPEN receiving session against a source document, pre-populating one EXPECTED line \
                    per still-open line of the purchase order it names.
                    Use this tool to start a line-by-line receiving workflow before recording actual quantities \
                    with receiveItemsIntoStaging; do not use createGoodsReceipt, which posts stock against a \
                    purchase order in one call without a session.
                    Preconditions: sourceDocumentId must be the UUID of an APPROVED or PARTIALLY_RECEIVED \
                    purchase order with at least one line still open, resolved from this service's \
                    purchase-order projection. The document type is detected from the id prefix - ASN when the \
                    id starts with ASN, PO otherwise - and only PO is supported; a receiving session cannot be \
                    created against an ASN, because no service owns ASN lines.
                    Each line's expected quantity is the order line's open quantity, so a partially received \
                    order never re-expects what has already arrived.
                    Required inputs: sourceDocumentId (non-blank string); entryMethod is optional and defaults to \
                    MANUAL, with SCAN the other accepted value.
                    Emits an INVENTORY_RECEIVING_SESSION_CREATE event; no stock is posted, the session only stages \
                    expected quantities until items are received.
                    Returns 404 when no such purchase order has been projected, 400 when it is already fully \
                    received, is not in a receivable status, or entryMethod is not a known value, and 422 when \
                    the source document type is not a purchase order.
                    """,
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "201",
            description = "Receiving session created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceivingSessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure or source document already fully received",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required receiving:create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such purchase order has been projected into pos-inventory",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "UNSUPPORTED_SOURCE_DOCUMENT_TYPE - receiving sessions are supported for purchase orders" + " only",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceivingSessionResponse> createReceivingSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Source document to receive against and the line entry method.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateReceivingSessionRequest.class),
                                            examples =
                                                    @ExampleObject(name = "Manual PO receiving session", value = """
                                                                    {"sourceDocumentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "entryMethod":"MANUAL"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateReceivingSessionRequest request) {

        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));

        ReceivingSessionResponse response = receivingService.createReceivingSession(request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/sessions/{sessionId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RECEIVING_VIEW + "')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_GET", apiVersion = "1")
    @Operation(
            operationId = "getReceivingSession",
            summary = "Get Receiving Session",
            description = """
                    Returns one receiving session with its status, entry method and per-line expected and received \
                    quantities.
                    Use this tool when the sessionId is already known, for example to check line statuses before \
                    or after receiveItemsIntoStaging; use getGoodsReceipt instead for one-shot receipts posted \
                    outside a session.
                    Preconditions: the receiving session must exist.
                    Required inputs: sessionId (UUIDv7) path parameter; there is no request body.
                    Emits an INVENTORY_RECEIVING_SESSION_GET audit event; no stock state changes, this is a \
                    read-only projection.
                    Returns 404 when no receiving session exists for the supplied id.
                    """,
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Receiving session found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceivingSessionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required receiving:view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Receiving session not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceivingSessionResponse> getReceivingSession(
            @Parameter(description = "Receiving session identifier", required = true) @PathVariable UUID sessionId) {

        ReceivingSessionResponse response = receivingService.getReceivingSession(sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Records actual received quantities for a session, generating ledger entries.
     * ADR-0017: Returns 200 OK (updating existing resource).
     * ADR-0018: actorUserId from authenticated security context.
     */
    @PostMapping("/sessions/{sessionId}/receive")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:complete"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RECEIVING_COMPLETE + "')")
    @EmitEvent(id = "INVENTORY_RECEIVING_SESSION_COMPLETE", apiVersion = "1")
    @Operation(
            operationId = "receiveItemsIntoStaging",
            summary = "Receive Items Into Staging",
            description = """
                    Records actual received quantities for receiving session lines, posting a GOODS_RECEIPT ledger \
                    entry into the staging location per line and a SHORTAGE or OVERAGE variance record for every \
                    expected-vs-received mismatch.
                    Use this tool to book arrived stock into an open session; do not use crossDockReceivingLine, \
                    which routes a line straight to a workorder instead of staging, and do not use \
                    createGoodsReceipt, the sessionless PO receipt.
                    Preconditions: the receiving session must exist; lines naming a lineId not present in the \
                    session are skipped rather than failing.
                    Required inputs: sessionId (UUIDv7) path parameter and lines (non-empty), each naming lineId \
                    plus either a whole-number receivedQuantity in base UoM or the documentUom/documentQuantity \
                    pair; lotNumber is mandatory for LOT-tracked products (expirationDate optionally stamps a new \
                    lot) and serialNumbers must enumerate exactly the received quantity for SERIAL-tracked \
                    products.
                    Emits an INVENTORY_RECEIVING_SESSION_COMPLETE event, marks each line RECEIVED, RECEIVED_SHORT \
                    or RECEIVED_OVER, and moves the session to COMPLETED when every line is settled or IN_PROGRESS \
                    otherwise.
                    Receiving is cumulative: each call adds to the line's receivedQuantity, and every call that \
                    receives against a purchase order is recorded as one goods receipt (linked to the session and \
                    its lines), visible through getGoodsReceipt, and publishes goodsreceipt.recorded from it.
                    Over-receipt: a line whose cumulative received quantity would pass its expected quantity is \
                    rejected with 422 OVER_RECEIPT_NOT_PERMITTED, before anything is posted, unless the caller \
                    holds inventory:goods_receipt:override; with the override it is accepted as RECEIVED_OVER \
                    with an OVERAGE variance.
                    Idempotency: send an Idempotency-Key header (or the idempotencyKey body field; the header wins, \
                    and both present and different is a 400). A retry with the same key and payload posts and \
                    publishes nothing and returns the original response; the same key with a different payload is \
                    409 IDEMPOTENCY_CONFLICT. Without a key a retry is not recognised.
                    Returns 404 when the receiving session does not exist, 400 when a quantity is missing or not a \
                    whole number, 409 on an idempotency-key conflict, and 422 when the receipt is an over-receipt \
                    without the override, a documentUom has no conversion path, a LOT-tracked line omits \
                    lotNumber, or a serialized line's serial count mismatches the received quantity.
                    """,
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Items received successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceiveItemsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required receiving:complete authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Receiving session not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT - the Idempotency-Key was already used for a different payload",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "OVER_RECEIPT_NOT_PERMITTED - the cumulative received quantity exceeds the expected quantity and the caller lacks inventory:goods_receipt:override; or LOT_NUMBER_REQUIRED, SERIAL_COUNT_MISMATCH, UOM_CONVERSION_UNDEFINED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceiveItemsResponse> receiveItemsIntoStaging(
            @Parameter(description = "Receiving session identifier", required = true) @PathVariable UUID sessionId,
            @Parameter(
                            description =
                                    "Optional key that makes retries idempotent; takes precedence over the body's idempotencyKey")
                    @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                    String idempotencyKeyHeader,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Actual received quantities, lot and serial data for the session lines.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ReceiveItemsRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Lot-tracked line received in full",
                                                            value = """
                                                                    {"lines":[{"lineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a06",
                                                                      "receivedQuantity":8,
                                                                      "lotNumber":"LOT-2026-0042",
                                                                      "expirationDate":"2027-01-31"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ReceiveItemsRequest request) {

        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));

        request.setIdempotencyKey(resolveIdempotencyKey(idempotencyKeyHeader, request.getIdempotencyKey()));
        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Cross-docks a receiving line directly to a workorder.
     * ADR-0017: 200 OK on success, 400 for closed workorder, 403 for part mismatch
     * without permission.
     * ADR-0018: actorUserId from authenticated security context.
     * ADR-0001: Dual ledger entries (GOODS_RECEIVED + GOODS_ISSUE) created
     * atomically.
     */
    @PostMapping("/sessions/{sessionId}/lines/{lineId}/cross-dock")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:complete", "inventory:issue:parts"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RECEIVING_COMPLETE + "') and hasAuthority('"
            + InventoryPermissionRegistry.ISSUE_PARTS + "')")
    @EmitEvent(id = "INVENTORY_RECEIVING_CROSSDOCK", apiVersion = "1")
    @Operation(
            operationId = "crossDockReceivingLine",
            summary = "Cross-Dock Receiving Line To Workorder",
            description = """
                    Cross-docks received quantity from a receiving session line directly to a workorder line, \
                    posting paired GOODS_RECEIPT and GOODS_ISSUE ledger entries atomically at the cross-dock \
                    location so on-hand nets to zero.
                    Use this tool when arrived stock should bypass staging and go straight to the demanding \
                    workorder; do not use receiveItemsIntoStaging, which books the quantity into the staging \
                    location for later putaway.
                    Preconditions: the session and line must exist, the workorder must not be COMPLETED, CANCELLED \
                    or CLOSED, the cumulative received quantity may not exceed the line's expected quantity unless \
                    the caller holds inventory:goods_receipt:override (the same over-receipt guard as \
                    receiveItemsIntoStaging, evaluated before any ledger write), and \
                    the line's product must match the workorder line's demanded product unless the caller holds \
                    inventory:override:part-match.
                    Required inputs: sessionId and lineId (UUIDv7) path parameters plus workorderId, \
                    workorderLineId and a positive quantity; lotNumber is mandatory for LOT-tracked products, \
                    falling back to the lot already keyed on the line, and notes is optional.
                    Each cross-dock is recorded as one goods receipt (linked to the session line) and publishes \
                    goodsreceipt.recorded from it. Idempotency: send an Idempotency-Key header (or the \
                    idempotencyKey body field; the header wins, both present and different is a 400); a retry \
                    with the same key and payload posts and publishes nothing and returns the original response, \
                    and the same key with a different payload is 409 IDEMPOTENCY_CONFLICT.
                    Emits an INVENTORY_RECEIVING_CROSSDOCK event, stamps the workorder reference on the line, \
                    updates the line to RECEIVED, RECEIVED_SHORT or RECEIVED_OVER, and completes the session when \
                    every line is settled.
                    Returns 404 when the session or line is not found, 400 when the workorder is closed or the \
                    workorder line belongs to another workorder, 409 on an idempotency-key conflict, 422 \
                    OVER_RECEIPT_NOT_PERMITTED when the cumulative quantity exceeds the expected quantity without \
                    the override, \
                    503 with a Retry-After header and WORKORDER_REPLICATION_PENDING when the workorder or its \
                    part line has not replicated from pos-workorder yet, 403 when the product mismatches the workorder demand \
                    without the override permission, and 422 when a LOT-tracked product resolves no lot number.
                    """,
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Cross-dock completed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CrossDockResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or closed workorder",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required authority or part-match override permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Receiving session or line not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT - the Idempotency-Key was already used for a different payload",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "OVER_RECEIPT_NOT_PERMITTED - the cumulative cross-docked quantity exceeds the expected quantity and the caller lacks inventory:goods_receipt:override; or LOT_NUMBER_REQUIRED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "WORKORDER_REPLICATION_PENDING: the workorder or its part line has not replicated from pos-workorder yet. Not-yet, not no: retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CrossDockResponse> crossDockLineToWorkorder(
            @Parameter(description = "Receiving session identifier", required = true) @PathVariable UUID sessionId,
            @Parameter(description = "Receiving line identifier", required = true) @PathVariable UUID lineId,
            @Parameter(
                            description =
                                    "Optional key that makes retries idempotent; takes precedence over the body's idempotencyKey")
                    @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                    String idempotencyKeyHeader,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Workorder destination and quantity for the cross-docked stock.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CrossDockRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Cross-dock to urgent workorder",
                                                            value = """
                                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a07",
                                                                     "workorderLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a08",
                                                                     "quantity":12,
                                                                     "notes":"Cross-docked at dock door 3",
                                                                     "lotNumber":"LOT-2026-0042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CrossDockRequest request) {

        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));

        request.setIdempotencyKey(resolveIdempotencyKey(idempotencyKeyHeader, request.getIdempotencyKey()));
        CrossDockResponse response = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, actorUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * The idempotency key of a receive or cross-dock call (#2455): the {@code Idempotency-Key}
     * header, falling back to the body's {@code idempotencyKey}. Both present and different is a
     * 400, since the caller would otherwise not know which one a retry is keyed on.
     */
    static String resolveIdempotencyKey(String header, String body) {
        String headerKey = header == null || header.isBlank() ? null : header.trim();
        String bodyKey = body == null || body.isBlank() ? null : body.trim();
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header and idempotencyKey body field are both set and differ");
        }
        return headerKey != null ? headerKey : bodyKey;
    }

    @GetMapping("/workorders")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:receiving:complete", "inventory:issue:parts"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RECEIVING_COMPLETE + "') and hasAuthority('"
            + InventoryPermissionRegistry.ISSUE_PARTS + "')")
    @EmitEvent(id = "INVENTORY_RECEIVING_WORKORDER_SEARCH", apiVersion = "1")
    @Operation(
            operationId = "searchCrossDockWorkorders",
            summary = "Search Cross-Dock Workorders",
            description = """
                    Searches the workorders eligible to receive a cross-docked receiving line: status not \
                    COMPLETED, CANCELLED or CLOSED, and at least one demanded part line.
                    Use this tool to find the workorderId/workorderLineId to pass to crossDockReceivingLine; do \
                    not use it for workorders with no part lines, which are never eligible and never returned.
                    Preconditions: none; an unmatched query yields an empty array.
                    Required inputs: none. Optional query parameter query matches workorderNumber \
                    (case-insensitive contains) or an exact workorder UUID; a blank or omitted query returns up \
                    to 50 most-recently-updated eligible workorders.
                    Read-only: no state changes. Emits an INVENTORY_RECEIVING_WORKORDER_SEARCH event (the \
                    module's read-audit convention for a search endpoint) even though nothing is written.
                    Returns 200 with an empty array when nothing matches.
                    """,
            tags = {"Receiving"})
    @ApiResponse(
            responseCode = "200",
            description = "Matching workorders returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema =
                                                    @Schema(implementation = CrossDockWorkorderSearchResultDto.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<CrossDockWorkorderSearchResultDto>> searchCrossDockWorkorders(
            @Parameter(description = "Workorder number fragment or exact workorder UUID")
                    @RequestParam(required = false)
                    String query) {
        return ResponseEntity.ok(receivingService.searchCrossDockWorkorders(query));
    }
}
