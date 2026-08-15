package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillMatchCandidateResponse;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.service.VendorBillService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                    Emits an ACCOUNTING_VENDOR_BILL_CREATE event; a vendor-directory sync failure is logged \
                    and never fails bill creation.
                    Returns 201 with the created (or already-existing) bill, and 400 when the payload fails \
                    validation.
                    """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "201",
            description = "Vendor bill created",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
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
                    Runs the three-way match of a received vendor invoice against pending goods-received \
                    bills: a HIGH_CONFIDENCE match with consistent quantities and prices auto-approves the \
                    bill, while a discrepancy, a MEDIUM confidence score or an AMBIGUOUS match parks it in \
                    MATCH_EXCEPTION.
                    Use this tool when a vendor invoice arrives; do not use \
                    createVendorBillFromGoodsReceived, which records the receipt, and use \
                    resolveVendorBillMatchException or selectVendorBillMatchCandidate to clear exceptions.
                    Preconditions: a bill in PENDING_RECEIPT_MATCH must exist for the vendor; an ambiguous \
                    outcome persists scored candidates for later operator selection.
                    Required inputs: eventId, organizationId and vendorId (UUIDs), invoiceReference, \
                    invoiceDate and lineItems; dueDate is optional.
                    Emits an ACCOUNTING_VENDOR_BILL_MATCH event; the returned bill's status conveys the \
                    outcome (APPROVED or MATCH_EXCEPTION), so callers must inspect it rather than assume \
                    approval.
                    Returns 400 when no pending receipt matches the invoice or the payload fails validation.
                    """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "201",
            description = "Invoice matched and bill created/updated",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
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
     * Resolve a bill match exception (ACCEPT/VOID/CORRECT).
     *
     * POST /v1/accounting/vendor-bills/{billId}/resolve-exception
     *
     * @param billId  the vendor bill ID
     * @param request the exception resolution request
     * @return updated bill response with new status
     */
    @PostMapping("/{billId}/resolve-exception")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    @Operation(
            operationId = "resolveVendorBillMatchException",
            summary = "Resolve Vendor Bill Match Exception",
            description = """
                    Resolves a vendor bill parked in MATCH_EXCEPTION with an operator decision: ACCEPT \
                    approves the bill despite the discrepancy, VOID rejects it, and CORRECT sends it back for \
                    correction.
                    Use this tool for quantity, price or medium-confidence exceptions on one identified bill; \
                    do not use selectVendorBillMatchCandidate, which resolves an ambiguous match by picking \
                    among several candidate bills.
                    Preconditions: the bill must exist and be in MATCH_EXCEPTION status.
                    Required inputs: billId (UUID) as a path parameter, resolutionAction (ACCEPT, VOID or \
                    CORRECT), reason and operatorId, all recorded for audit.
                    Emits an ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE event.
                    Returns 400 when the bill is not found, is not in MATCH_EXCEPTION status, or the action \
                    is not one of ACCEPT, VOID or CORRECT.
                    """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Exception resolved",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Vendor bill not found")
    public ResponseEntity<VendorBillResponse> resolveMatchException(
            @Parameter(description = "Vendor bill identifier", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Operator decision (ACCEPT, VOID or CORRECT) with the audit reason.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Accept variance", value = """
                                                                    {"resolutionAction":"ACCEPT",
                                                                     "reason":"Invoice variance approved by manager",
                                                                     "operatorId":"manager-001"}
                                                                    """)))
                    @NonNull
                    @Valid
                    @RequestBody
                    ExceptionResolutionRequest request) {
        log.info(
                "Received request to resolve match exception | billId={} | action={}",
                billId,
                request.getResolutionAction());

        VendorBillResponse response = vendorBillService.resolveMatchException(
                billId, request.getResolutionAction(), request.getReason(), request.getOperatorId());

        return ResponseEntity.ok(response);
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
                    Returns one vendor bill with its status, amounts, match metadata and approval history.
                    Use this tool when the bill id is already known; use getVendorBillByOriginEventId \
                    instead when only the goods-received event id is available, or listApBills to browse \
                    APPROVED bills.
                    Preconditions: the vendor bill must exist.
                    Required inputs: billId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_VENDOR_BILL_GET audit event; no state changes.
                    Returns 404 when no vendor bill exists for the supplied id.
                    """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor bill found",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Vendor bill not found")
    public ResponseEntity<VendorBillResponse> getBillById(
            @Parameter(description = "Vendor bill identifier", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID billId) {
        log.info("Received request to retrieve vendor bill | billId={}", billId);

        return vendorBillService
                .getBillById(billId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
    @ApiResponse(responseCode = "404", description = "Vendor bill not found")
    public ResponseEntity<VendorBillResponse> getBillByOriginEventId(
            @Parameter(description = "Origin event identifier", example = "550e8400-e29b-41d4-a716-446655440010")
                    @NonNull
                    @PathVariable
                    UUID eventId) {
        log.info("Received request to retrieve vendor bill by origin event | eventId={}", eventId);

        return vendorBillService
                .getBillByOriginEventId(eventId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
            content = @Content(schema = @Schema(implementation = VendorBillMatchCandidateResponse.class)))
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
     * Select a match candidate to approve the corresponding vendor bill.
     *
     * POST /v1/accounting/vendor-bills/match-candidates/{candidateId}/select
     *
     * @param candidateId the candidate to select
     * @param request     selection request with operator ID
     * @return updated vendor bill response
     */
    @PostMapping("/match-candidates/{candidateId}/select")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATE_SELECT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    @Operation(
            operationId = "selectVendorBillMatchCandidate",
            summary = "Select Vendor Bill Match Candidate",
            description = """
                    Selects one candidate from an ambiguous invoice match, approving the corresponding \
                    vendor bill and marking the candidate set resolved.
                    Use this tool after reviewing listVendorBillMatchCandidates; do not use \
                    resolveVendorBillMatchException, which handles discrepancy exceptions on a single bill.
                    Preconditions: the candidate must exist and must not already be resolved.
                    Required inputs: candidateId (UUID) as a path parameter and operatorId in the body, \
                    recorded as the approver.
                    Emits an ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATE_SELECT event.
                    Returns 400 when the candidate is missing or already resolved (mapped as \
                    VALIDATION_ERROR, not 404).
                    """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Candidate selected",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Candidate not found")
    public ResponseEntity<VendorBillResponse> selectMatchCandidate(
            @Parameter(description = "Match candidate identifier", example = "550e8400-e29b-41d4-a716-446655440030")
                    @NonNull
                    @PathVariable
                    UUID candidateId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Operator making the candidate selection, recorded as the approver.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Select candidate",
                                                            value = "{\"operatorId\":\"advisor-001\"}")))
                    @NonNull
                    @Valid
                    @RequestBody
                    CandidateSelectionRequest request) {
        log.info(
                "Received request to select match candidate | candidateId={} | operator={}",
                candidateId,
                request.getOperatorId());

        VendorBillResponse response = vendorBillService.selectMatchCandidate(candidateId, request.getOperatorId());
        return ResponseEntity.ok(response);
    }

    /**
     * DTO for match candidate selection requests.
     */
    @Schema(description = "Request payload for selecting a match candidate")
    public static class CandidateSelectionRequest {
        @Schema(
                description = "Operator identifier performing selection",
                example = "advisor-001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String operatorId;

        public CandidateSelectionRequest() {}

        public CandidateSelectionRequest(@NonNull String operatorId) {
            this.operatorId = operatorId;
        }

        @NonNull
        public String getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(@NonNull String operatorId) {
            this.operatorId = operatorId;
        }
    }

    /**
     * DTO for exception resolution requests.
     */
    @Schema(description = "Request payload for resolving a vendor bill match exception")
    public static class ExceptionResolutionRequest {
        @Schema(
                description = "Resolution action",
                example = "ACCEPT",
                allowableValues = {"ACCEPT", "VOID", "CORRECT"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String resolutionAction; // ACCEPT, VOID, CORRECT

        @Schema(
                description = "Reason for chosen resolution",
                example = "Invoice variance approved by manager",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String reason;

        @Schema(
                description = "Operator identifier performing resolution",
                example = "manager-001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String operatorId;

        public ExceptionResolutionRequest() {}

        public ExceptionResolutionRequest(
                @NonNull String resolutionAction, @NonNull String reason, @NonNull String operatorId) {
            this.resolutionAction = resolutionAction;
            this.reason = reason;
            this.operatorId = operatorId;
        }

        @NonNull
        public String getResolutionAction() {
            return resolutionAction;
        }

        public void setResolutionAction(@NonNull String resolutionAction) {
            this.resolutionAction = resolutionAction;
        }

        @NonNull
        public String getReason() {
            return reason;
        }

        public void setReason(@NonNull String reason) {
            this.reason = reason;
        }

        @NonNull
        public String getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(@NonNull String operatorId) {
            this.operatorId = operatorId;
        }
    }
}
