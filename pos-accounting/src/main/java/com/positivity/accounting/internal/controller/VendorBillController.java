package com.positivity.accounting.internal.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillMatchCandidateResponse;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.service.VendorBillService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;

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
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
@Tag(name = "Vendor Bill API", description = "Endpoints for vendor bill creation, matching, retrieval, and exception resolution")
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
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(summary = "Create vendor bill from goods received event", description = "Creates a vendor bill from an inbound goods-received event payload")
    @ApiResponse(responseCode = "201", description = "Vendor bill created", content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<VendorBillResponse> createBillFromGoodsReceivedEvent(
            @NonNull @Valid @RequestBody GoodsReceivedEvent event) {
        log.info("Received request to create vendor bill from goods received event | eventId={} | vendorId={}",
                event.getEventId(), event.getVendorId());

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
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(summary = "Match vendor invoice", description = "Performs matching for a received vendor invoice and creates/updates bill state")
    @ApiResponse(responseCode = "201", description = "Invoice matched and bill created/updated", content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<VendorBillResponse> matchVendorInvoice(
            @NonNull @Valid @RequestBody VendorInvoiceReceivedEvent event) {
        log.info("Received request to perform three-way match | eventId={} | invoiceRef={}",
                event.getEventId(), event.getInvoiceReference());

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
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(summary = "Resolve bill match exception", description = "Resolves a matching exception with action ACCEPT, VOID, or CORRECT")
    @ApiResponse(responseCode = "200", description = "Exception resolved", content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Vendor bill not found")
    public ResponseEntity<VendorBillResponse> resolveMatchException(
            @Parameter(description = "Vendor bill identifier", example = "550e8400-e29b-41d4-a716-446655440001") @NonNull @PathVariable UUID billId,
            @NonNull @Valid @RequestBody ExceptionResolutionRequest request) {
        log.info("Received request to resolve match exception | billId={} | action={}",
                billId, request.getResolutionAction());

        VendorBillResponse response = vendorBillService.resolveMatchException(
                billId,
                request.getResolutionAction(),
                request.getReason(),
                request.getOperatorId());

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
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get vendor bill by id", description = "Retrieves a vendor bill by its unique identifier")
    @ApiResponse(responseCode = "200", description = "Vendor bill found", content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Vendor bill not found")
    public ResponseEntity<VendorBillResponse> getBillById(
            @Parameter(description = "Vendor bill identifier", example = "550e8400-e29b-41d4-a716-446655440001") @NonNull @PathVariable UUID billId) {
        log.info("Received request to retrieve vendor bill | billId={}", billId);

        return vendorBillService.getBillById(billId)
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
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get vendor bill by origin event id", description = "Retrieves a vendor bill by origin goods-received event identifier")
    @ApiResponse(responseCode = "200", description = "Vendor bill found", content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Vendor bill not found")
    public ResponseEntity<VendorBillResponse> getBillByOriginEventId(
            @Parameter(description = "Origin event identifier", example = "550e8400-e29b-41d4-a716-446655440010") @NonNull @PathVariable UUID eventId) {
        log.info("Received request to retrieve vendor bill by origin event | eventId={}", eventId);

        return vendorBillService.getBillByOriginEventId(eventId)
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
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "List match candidates", description = "Lists unresolved match candidates for an ambiguous invoice event")
    @ApiResponse(responseCode = "200", description = "Match candidates returned", content = @Content(schema = @Schema(implementation = VendorBillMatchCandidateResponse.class)))
    public ResponseEntity<List<VendorBillMatchCandidateResponse>> listMatchCandidates(
            @Parameter(description = "Invoice event identifier", example = "550e8400-e29b-41d4-a716-446655440020") @NonNull @PathVariable UUID invoiceEventId) {
        log.info("Received request to list match candidates | invoiceEventId={}", invoiceEventId);

        List<VendorBillMatchCandidateResponse> candidates = vendorBillService
                .listMatchCandidates(invoiceEventId);
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
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(summary = "Select match candidate", description = "Selects a candidate and approves corresponding vendor bill flow")
    @ApiResponse(responseCode = "200", description = "Candidate selected", content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(responseCode = "404", description = "Candidate not found")
    public ResponseEntity<VendorBillResponse> selectMatchCandidate(
            @Parameter(description = "Match candidate identifier", example = "550e8400-e29b-41d4-a716-446655440030") @NonNull @PathVariable UUID candidateId,
            @NonNull @Valid @RequestBody CandidateSelectionRequest request) {
        log.info("Received request to select match candidate | candidateId={} | operator={}",
                candidateId, request.getOperatorId());

        VendorBillResponse response = vendorBillService.selectMatchCandidate(
                candidateId, request.getOperatorId());
        return ResponseEntity.ok(response);
    }

    /**
     * DTO for match candidate selection requests.
     */
    @Schema(description = "Request payload for selecting a match candidate")
    public static class CandidateSelectionRequest {
        @Schema(description = "Operator identifier performing selection", example = "advisor-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String operatorId;

        public CandidateSelectionRequest() {
        }

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
        @Schema(description = "Resolution action", example = "ACCEPT", allowableValues = { "ACCEPT", "VOID",
                "CORRECT" }, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String resolutionAction; // ACCEPT, VOID, CORRECT
        @Schema(description = "Reason for chosen resolution", example = "Invoice variance approved by manager", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String reason;
        @Schema(description = "Operator identifier performing resolution", example = "manager-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String operatorId;

        public ExceptionResolutionRequest() {
        }

        public ExceptionResolutionRequest(@NonNull String resolutionAction, @NonNull String reason,
                @NonNull String operatorId) {
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
