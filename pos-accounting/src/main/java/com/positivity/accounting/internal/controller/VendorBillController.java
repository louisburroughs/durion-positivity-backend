package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillListRow;
import com.positivity.accounting.internal.dto.VendorBillMatchCandidateResponse;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.accounting.internal.service.VendorBillService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST Controller for Vendor Bill lifecycle management.
 * Exposes endpoints for bill creation, three-way matching, and exception
 * resolution.
 *
 * @see VendorBillService
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/vendor-bills")
@RequiredArgsConstructor
@Tag(
        name = "Vendor Bill API",
        description = "Endpoints for vendor bill creation, matching, retrieval, and exception resolution")
@Validated
public class VendorBillController {

    private final VendorBillService vendorBillService;
    private final VendorBillApprovalService approvalService;

    /**
     * Create a vendor bill from a goods received event.
     *
     * POST /v1/accounting/vendor-bills
     *
     * @param event the goods received event payload
     * @return created bill response with 201 status
     */
    @PostMapping
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_CREATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    @Operation(
            operationId = "createVendorBillFromGoodsReceived",
            summary = "Create Vendor Bill From Goods Received",
            description = """
                Creates a vendor bill in PENDING_RECEIPT_MATCH status from a goods-received event, \
                totaling the received line items and syncing the vendor into the AP vendor directory.
                Use this tool when goods arrive against a purchase order; do not use matchVendorInvoice, \
                which is the later step that matches the vendor's invoice against this pending bill.
                Preconditions: none; a duplicate eventId is ignored and the existing bill is returned \
                instead of creating a second one.
                Required inputs: eventId, organizationId, purchaseOrderId and vendorId (UUIDs), \
                receivedDate, and lineItems each with productId, description, quantity and unitPrice; \
                vendorName and dimensions are optional.
                Emits an ACCOUNTING_VENDOR_BILL_CREATE event and posts nothing (a bill posts once, at \
                approval); a vendor-directory sync failure is logged and never fails bill creation.
                Returns 201 with the created (or already-existing) bill, and 400 when the payload fails \
                validation.
                Returns 409 AP_BILL_DUPLICATE when a live bill (any status except VOIDED or REJECTED) \
                already holds the same vendor, bill date and bill number, compared ignoring case, \
                spacing, punctuation and leading zeros; referenceId is the existing bill's vendorBillId \
                and nothing is created. A replayed eventId is never a duplicate.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "201",
            description = "Vendor bill created",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_DUPLICATE: a live bill already holds this vendor, bill number and bill date; "
                    + "referenceId is the existing bill's vendorBillId",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> createBillFromGoodsReceivedEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Goods-received event payload that seeds a pending vendor bill.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Goods received", value = """
                                                {"eventId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                 "organizationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                 "purchaseOrderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d",
                                                 "vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e",
                                                 "vendorName":"Acme Parts Co",
                                                 "receivedDate":"2026-08-13T09:30:00",
                                                 "lineItems":[
                                                   {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f",
                                                    "description":"Brake pads",
                                                    "quantity":10,
                                                    "unitPrice":24.99}]}
                                                """)))
                    @NonNull
                    @Valid
                    @RequestBody
                    GoodsReceivedEvent event) {
        log.info(
                "Received request to create vendor bill from goods received event | eventId={} | vendorId={}",
                event.getEventId(),
                event.getVendorId());

        VendorBillResponse response = vendorBillService.handleGoodsReceivedEvent(event);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Process vendor invoice and perform three-way match.
     *
     * POST /v1/accounting/vendor-bills/match
     *
     * @param event the vendor invoice received event payload
     * @return bill response with match result (201 if successful, 400 if exception)
     */
    @PostMapping("/match")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    @Operation(
            operationId = "matchVendorInvoice",
            summary = "Match Vendor Invoice",
            description = """
                Runs the three-way match of a received vendor invoice against pending goods-received bills (never \
                an EDI bill): a HIGH match (score 70 or more) within tolerance sends the bill to AWAITING_APPROVAL \
                with submittedBy SYSTEM and never approves it, a MEDIUM score or a discrepancy parks it in \
                MATCH_EXCEPTION, and an AMBIGUOUS match keeps the scored candidates for a person to select one; \
                nothing is posted.
                Every routed single match takes the invoice's number and its invoiceDate as the bill date (AW45) \
                and keeps what the vendor billed (the billed total and each line's billed quantity and price) and \
                an append-only evidence record with the receipt date, the score, the points per criterion (amount \
                40 against the received total, products 30, date 20, purchase order 5) and the line comparison.
                Use this tool when a vendor invoice arrives; do not use createVendorBillFromGoodsReceived, which \
                records the receipt, and use resolveVendorBillMatchException or selectVendorBillMatchCandidate \
                to clear exceptions.
                Preconditions: a bill in PENDING_RECEIPT_MATCH must exist for the vendor.
                Required inputs: eventId, organizationId and vendorId (UUIDs), invoiceReference, invoiceDate and \
                lineItems; dueDate is optional.
                Emits an ACCOUNTING_VENDOR_BILL_MATCH event and writes a VENDOR_BILL_MATCH_ROUTED audit row; the \
                returned bill's status conveys the outcome.
                Returns 400 when no pending receipt matches the invoice or the payload fails validation (a missing \
                invoiceDate included), 409 AP_BILL_DUPLICATE when another live bill (any status except VOIDED or \
                REJECTED) of the vendor already holds the invoiceReference on the invoiceDate, compared ignoring \
                case, spacing, punctuation and leading zeros (referenceId names it, and the receipt bill is left \
                untouched), the generic 409 DUPLICATE_RESOURCE when a concurrent writer takes the number between \
                the check and the commit, and 409 OPTIMISTIC_LOCK when the matched bill was decided meanwhile (send \
                the invoice again).
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "201",
            description = "Invoice matched and bill created/updated",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_DUPLICATE: another live bill of this vendor already holds this invoice reference "
                    + "on the invoice date; referenceId is that bill's vendorBillId. DUPLICATE_RESOURCE, with no "
                    + "referenceId, when a concurrent writer takes the number between the check and the commit. "
                    + "OPTIMISTIC_LOCK when the matched bill was decided meanwhile",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> matchVendorInvoice(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Vendor invoice payload to three-way match against pending receipt bills.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Vendor invoice received", value = """
                                                {"eventId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                 "organizationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                 "vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e",
                                                 "invoiceReference":"INV-88421",
                                                 "invoiceDate":"2026-08-12T00:00:00",
                                                 "dueDate":"2026-09-11T00:00:00",
                                                 "lineItems":[
                                                   {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f",
                                                    "description":"Brake pads",
                                                    "quantity":10,
                                                    "unitPrice":24.99}]}
                                                """)))
                    @NonNull
                    @Valid
                    @RequestBody
                    VendorInvoiceReceivedEvent event) {
        log.info(
                "Received request to perform three-way match | eventId={} | invoiceRef={}",
                event.getEventId(),
                event.getInvoiceReference());

        VendorBillResponse response = vendorBillService.handleVendorInvoiceReceivedEvent(event);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get vendor bill by bill ID.
     *
     * GET /v1/accounting/vendor-bills/{billId}
     *
     * @param billId the vendor bill ID
     * @return bill response with 200 status, or 404 if not found
     */
    @GetMapping("/{billId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "getVendorBillById",
            summary = "Get Vendor Bill By Id",
            description = """
                Returns one vendor bill as the review screen reads it: status, amounts (with the vendor's net and \
                tax) and open amount, channel, the submission and (once approved) the approval, the rejection, the \
                status explanation, the latest match evidence, the open candidates of an ambiguous match (each \
                with candidateId and invoiceEventId), re-issues held against it, the received lines with what was \
                billed, the checks (MATCHED_TO_DELIVERY, WITHIN_PRICE_TOLERANCE, TOTALS_ADD_UP and, on an EDI bill \
                classified GOODS, OPEN_DELIVERIES_FROM_VENDOR), the decisions the caller may take now \
                (availableActions) and the posting (journalEntryReference, postingDate, postingDateRule, \
                roundingAdjustment, difference, reversalReference).
                Use this tool when the bill id is already known; use getVendorBillByOriginEventId \
                instead when only the goods-received event id is available, or listVendorBillsByStage to \
                browse a stage.
                Preconditions: the vendor bill must exist.
                Required inputs: billId (UUID) as a path parameter; there is no request body.
                Emits an ACCOUNTING_VENDOR_BILL_GET audit event; no state changes.
                Returns 404 VENDOR_BILL_NOT_FOUND when no vendor bill exists for the supplied id, 401 without a \
                valid token, and 403 FORBIDDEN without accounting:ap:view.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor bill found",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> getBillById(
            @Parameter(description = "Vendor bill identifier", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID billId) {
        log.info("Received request to retrieve vendor bill | billId={}", billId);

        return ResponseEntity.ok(approvalService.getBill(billId));
    }

    /**
     * Get vendor bill by origin event ID.
     *
     * GET /v1/accounting/vendor-bills/event/{eventId}
     *
     * @param eventId the origin event ID (from GoodsReceivedEvent)
     * @return bill response with 200 status, or 404 if not found
     */
    @GetMapping("/event/{eventId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_GET_BY_EVENT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "getVendorBillByOriginEventId",
            summary = "Get Vendor Bill By Origin Event",
            description = """
                Returns the vendor bill created from a specific goods-received event, using the event id \
                recorded at bill creation.
                Use this tool to check whether a goods-received event was already billed, for example \
                before replaying it; use getVendorBillById instead when the bill id is known.
                Preconditions: a bill must have been created from the event.
                Required inputs: eventId (UUID of the origin GoodsReceivedEvent) as a path parameter; \
                there is no request body.
                Emits an ACCOUNTING_VENDOR_BILL_GET_BY_EVENT audit event; no state changes.
                Returns 404 when no vendor bill originates from the supplied event id.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor bill found",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Vendor bill not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> getBillByOriginEventId(
            @Parameter(description = "Origin event identifier", example = "550e8400-e29b-41d4-a716-446655440010")
                    @NonNull
                    @PathVariable
                    UUID eventId) {
        log.info("Received request to retrieve vendor bill by origin event | eventId={}", eventId);

        return vendorBillService
                .getBillByOriginEventId(eventId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor bill not found"));
    }

    /**
     * List unresolved match candidates for an ambiguous invoice match.
     *
     * GET /v1/accounting/vendor-bills/match-candidates/{invoiceEventId}
     *
     * @param invoiceEventId the invoice event that triggered the ambiguous match
     * @return list of scored candidates ordered by score descending
     */
    @GetMapping("/match-candidates/{invoiceEventId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATES_LIST", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "listVendorBillMatchCandidates",
            summary = "List Vendor Bill Match Candidates",
            description = """
                Lists the unresolved, scored candidate bills persisted when an invoice match came back \
                AMBIGUOUS, ordered by score descending.
                Use this tool to review the choices before calling selectVendorBillMatchCandidate; do \
                not use resolveVendorBillMatchException, which handles single-bill discrepancies rather \
                than ambiguity.
                Preconditions: a matchVendorInvoice call for this invoice event must have produced an \
                AMBIGUOUS outcome.
                Required inputs: invoiceEventId (UUID of the triggering invoice event) as a path \
                parameter; there is no request body.
                Emits an ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATES_LIST audit event; no state changes.
                Returns 200 with an empty list when no unresolved candidates exist for the event.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Match candidates returned",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = VendorBillMatchCandidateResponse.class))))
    public ResponseEntity<List<VendorBillMatchCandidateResponse>> listMatchCandidates(
            @Parameter(description = "Invoice event identifier", example = "550e8400-e29b-41d4-a716-446655440020")
                    @NonNull
                    @PathVariable
                    UUID invoiceEventId) {
        log.info("Received request to list match candidates | invoiceEventId={}", invoiceEventId);

        List<VendorBillMatchCandidateResponse> candidates = vendorBillService.listMatchCandidates(invoiceEventId);
        return ResponseEntity.ok(candidates);
    }

    /**
     * List vendor bills due in a date window, optionally filtered by status (Wave 2 E9, issue
     * #1597).
     *
     * GET /v1/accounting/vendor-bills
     *
     * @param dueFrom  window start (inclusive)
     * @param dueTo    window end (inclusive)
     * @param status   optional status filter
     * @param pageable page number/size (size capped server-side; sort is server-controlled)
     * @return page of matching bills, dueDate ascending
     */
    @GetMapping
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_LIST_VIEW", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:analytics:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.ANALYTICS_VIEW + "')")
    @Operation(
            operationId = "listVendorBills",
            summary = "List Vendor Bills By Due Date",
            description = """
                Lists vendor bills whose due date falls in [dueFrom, dueTo], optionally filtered by \
                status, ordered by due date ascending.
                Use this tool to browse or triage upcoming/overdue payables across vendors; do not use \
                listApBills for this, which is scoped to APPROVED-only bills sorted for payment \
                selection, and use getVendorBillById when the bill id is already known.
                Preconditions: none beyond the caller holding accounting:analytics:view.
                Required inputs: dueFrom and dueTo (ISO dates, dueTo on or after dueFrom); the window \
                cannot exceed 366 days, to bound the scan. status is an optional filter (PENDING_RECEIPT_MATCH, \
                MATCH_EXCEPTION, CURRENCY_HOLD, APPROVED, REJECTED, PAID, VOIDED); page/size/sort are standard, though \
                the due-date-ascending sort is server-controlled and any caller-supplied sort is ignored.
                Emits an ACCOUNTING_VENDOR_BILL_LIST_VIEW audit event; no state changes.
                Returns 400 when dueTo is before dueFrom, the window exceeds 366 days, or status is not \
                a recognized VendorBillStatus value.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(responseCode = "200", description = "Vendor bills retrieved successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid date range, window too wide, or unrecognized status",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<VendorBillListRow>> listVendorBills(
            @Parameter(description = "Due-date window start (YYYY-MM-DD)", required = true, example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate dueFrom,
            @Parameter(description = "Due-date window end (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate dueTo,
            @Parameter(description = "Optional bill status filter", example = "APPROVED")
                    @RequestParam(required = false)
                    VendorBillStatus status,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        Page<VendorBillListRow> bills = vendorBillService.listByDueDateWindow(dueFrom, dueTo, status, pageable);
        return ResponseEntity.ok(bills);
    }
}
