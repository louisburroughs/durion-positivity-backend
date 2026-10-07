package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
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

/**
 * The vendor-bill approval lifecycle (CAP:550 S12, #2509; SPEC-accounting-workspace §4.3, §5.2, §7.1; AW8, AW37-AW43):
 * submit, approve, reject, resolve a match exception, select a match candidate, void an approved bill, and the
 * stage reads of Bills to pay. The actor of every command is the caller (ADR-0018); no body carries one.
 */
@RestController
@RequestMapping("/v1/accounting/vendor-bills")
@RequiredArgsConstructor
@Tag(
        name = "Vendor Bill API",
        description = "Endpoints for vendor bill creation, matching, retrieval, and exception resolution")
@Validated
public class VendorBillApprovalController {

    private static final String BILL_ID = "Vendor bill identifier";
    private static final String BILL_ID_EXAMPLE = "550e8400-e29b-41d4-a716-446655440001";

    private final VendorBillApprovalService approvalService;

    @PostMapping("/{billId}/submit-for-approval")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_SUBMIT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve", "accounting:ap:approve_over_limit"})
    @PreAuthorize("hasAnyAuthority('" + AccountingPermissions.AP_APPROVE + "', '"
            + AccountingPermissions.AP_APPROVE_OVER_LIMIT + "')")
    @Operation(
            operationId = "submitVendorBillForApproval",
            summary = "Submit Vendor Bill For Approval",
            description = """
                Sends a vendor bill in PENDING_RECEIPT_MATCH or MATCH_EXCEPTION for approval: it moves to \
                AWAITING_APPROVAL with the caller as submittedBy. From PENDING_RECEIPT_MATCH this is "send \
                without a delivery match" (service bills, shop supplies, EDI bills that will never have a \
                receipt); from MATCH_EXCEPTION it resolves the exception for a person to approve.
                Use this tool when a clerk has checked a bill and wants it approved; do not use \
                approveVendorBill, which is the approver's decision, or resolveVendorBillMatchException, which \
                accepts, corrects or voids an exception directly.
                Preconditions: the bill is PENDING_RECEIPT_MATCH or MATCH_EXCEPTION; CURRENCY_HOLD and every \
                other status are refused. Nothing is posted.
                Required inputs: billId (UUID) as a path parameter and justification (at least 10 \
                characters); classification {debitClass, expenseMappingKey} is an optional proposal the \
                approver may keep.
                Emits ACCOUNTING_VENDOR_BILL_SUBMIT and writes a VENDOR_BILL_SUBMIT audit row.
                Returns 200 with the bill read, 400 JUSTIFICATION_REQUIRED for a missing or short \
                justification, 400 VALIDATION_ERROR for a malformed classification, 403 FORBIDDEN without \
                accounting:ap:approve or accounting:ap:approve_over_limit, 404 VENDOR_BILL_NOT_FOUND, and 409 \
                AP_BILL_NOT_APPROVABLE naming the bill's status.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Sent for approval",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED or VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_APPROVABLE: the bill's status does not allow it",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> submitForApproval(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Send without a delivery match",
                                                            value = """
                                                                {"justification":"Service bill, no delivery to match",
                                                                 "classification":{"debitClass":"EXPENSE",
                                                                  "expenseMappingKey":"EXPENSE_EQUIPMENT_REPAIRS"}}
                                                                """)))
                    @NonNull
                    @Valid
                    @RequestBody
                    VendorBillCommands.Submit request) {
        return ResponseEntity.ok(approvalService.submitForApproval(billId, request));
    }

    @PostMapping("/{billId}/approve")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_APPROVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve_over_limit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_APPROVE_OVER_LIMIT + "')")
    @Operation(
            operationId = "approveVendorBill",
            summary = "Approve Vendor Bill",
            description = """
                Approves a vendor bill in AWAITING_APPROVAL and posts it in the same transaction: a bill is \
                approved if and only if it posted (AW37). The entry credits accounts payable for the billed \
                gross and debits by class through the VENDOR_BILL posting category: receipt-matched lines \
                2100 at the received price with the price difference in 5050, unmatched goods 2100, \
                expenses the chosen EXPENSE_<CODE> key, US tax into the cost. It is dated on the bill date \
                when that is on or before today and its period is open, otherwise today; the read serves \
                postingDate and postingDateRule.
                Use this tool for the approver's decision on a bill sent for approval; do not use \
                submitVendorBillForApproval, which only sends it, or resolveVendorBillMatchException with \
                ACCEPT, which approves a bill still in MATCH_EXCEPTION.
                Preconditions: the bill is AWAITING_APPROVAL (CURRENCY_HOLD bills never are). Until approval \
                limits exist every bill needs accounting:ap:approve_over_limit.
                Required inputs: billId (UUID) as a path parameter. Optional: justification (at least 10 \
                characters), classification {debitClass GOODS|EXPENSE, expenseMappingKey} (required for a \
                bill without receipt-matched lines and for non-stock lines, else the one proposed at \
                submission), overrideJustification (at least 10 characters) to post into a CLOSED period \
                with accounting:period:override.
                Emits ACCOUNTING_VENDOR_BILL_APPROVE and writes a VENDOR_BILL_APPROVE audit row; a refused \
                posting writes one VENDOR_BILL_APPROVE_REFUSED row instead and changes nothing else.
                Returns 200 with the bill read, its posting included; 400 JUSTIFICATION_REQUIRED or \
                VALIDATION_ERROR; 403 FORBIDDEN; 404 VENDOR_BILL_NOT_FOUND; 409 AP_BILL_NOT_APPROVABLE naming \
                the status (a second approve included, which posts nothing); 422 AP_BILL_UNCLASSIFIED, \
                PERIOD_CLOSED, PERIOD_HARD_LOCKED or GL_MAPPING_NOT_CONFIGURED, each leaving the bill as it was.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Approved and posted",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED or VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_APPROVABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "AP_BILL_UNCLASSIFIED, PERIOD_CLOSED, PERIOD_HARD_LOCKED or GL_MAPPING_NOT_CONFIGURED;"
                    + " the approval is rolled back",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> approve(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Approve an EDI expense bill", value = """
                                                        {"justification":"Checked against the work order",
                                                         "classification":{"debitClass":"EXPENSE",
                                                          "expenseMappingKey":"EXPENSE_SHOP_SUPPLIES"}}
                                                        """)))
                    @NonNull
                    @Valid
                    @RequestBody
                    VendorBillCommands.Approve request) {
        return ResponseEntity.ok(approvalService.approve(billId, request));
    }

    @PostMapping("/{billId}/reject")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_REJECT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:reject"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_REJECT + "')")
    @Operation(
            operationId = "rejectVendorBill",
            summary = "Reject Vendor Bill",
            description = """
                Rejects a vendor bill in AWAITING_APPROVAL: it moves to REJECTED, terminal, with the caller \
                as rejectedBy and the reason recorded. Nothing was posted, so nothing is reversed.
                Use this tool when the approver refuses a bill; do not use voidApprovedVendorBill, which \
                voids a bill already approved, or resolveVendorBillMatchException with VOID, which voids a \
                bill still in MATCH_EXCEPTION.
                Preconditions: the bill is AWAITING_APPROVAL.
                Required inputs: billId (UUID) as a path parameter and reason (at least 10 characters).
                Emits ACCOUNTING_VENDOR_BILL_REJECT and writes a VENDOR_BILL_REJECT audit row.
                Returns 200 with the bill read, 400 JUSTIFICATION_REQUIRED for a missing or short reason, \
                403 FORBIDDEN without accounting:ap:reject, 404 VENDOR_BILL_NOT_FOUND, and 409 \
                AP_BILL_NOT_APPROVABLE naming the bill's status.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Rejected",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_APPROVABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> reject(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reject",
                                                            value = "{\"reason\":\"Vendor billed a delivery we"
                                                                    + " refused\"}")))
                    @NonNull
                    @Valid
                    @RequestBody
                    VendorBillCommands.Reject request) {
        return ResponseEntity.ok(approvalService.reject(billId, request));
    }

    @PostMapping("/{billId}/resolve-exception")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve", "accounting:ap:approve_over_limit", "accounting:ap:reject"})
    @PreAuthorize("hasAnyAuthority('" + AccountingPermissions.AP_APPROVE + "', '"
            + AccountingPermissions.AP_APPROVE_OVER_LIMIT + "', '" + AccountingPermissions.AP_REJECT + "')")
    @Operation(
            operationId = "resolveVendorBillMatchException",
            summary = "Resolve Vendor Bill Match Exception",
            description = """
                Resolves a vendor bill in MATCH_EXCEPTION: ACCEPT is an approval and posts the bill, exactly \
                as approveVendorBill does; CORRECT sends it back to PENDING_RECEIPT_MATCH and writes no \
                approval or rejection field; VOID voids it, recording the caller as rejectedBy (nothing was \
                posted, so nothing is reversed).
                Use this tool for a quantity, price or medium-confidence exception on one bill; do not use \
                selectVendorBillMatchCandidate, which resolves an ambiguous match by picking among several \
                bills, or submitVendorBillForApproval, which sends the bill to another person's approval.
                Preconditions: the bill is MATCH_EXCEPTION. Each action needs its own permission: ACCEPT \
                accounting:ap:approve_over_limit (every bill is over the default limit until approval limits \
                exist), CORRECT accounting:ap:approve or accounting:ap:approve_over_limit, VOID \
                accounting:ap:reject.
                Required inputs: billId (UUID) as a path parameter, resolutionAction (ACCEPT, CORRECT or \
                VOID) and reason (at least 10 characters). ACCEPT also takes classification and \
                overrideJustification as approveVendorBill does. The actor is the caller; an operatorId in \
                the body is ignored.
                Emits ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE and writes a \
                VENDOR_BILL_MATCH_EXCEPTION_RESOLVE audit row.
                Returns 200 with the bill read; 400 VALIDATION_ERROR for an unknown action, 400 \
                JUSTIFICATION_REQUIRED for a missing or short reason; 403 FORBIDDEN without the action's \
                permission; 404 VENDOR_BILL_NOT_FOUND; 409 AP_BILL_NOT_APPROVABLE naming the status; for \
                ACCEPT, 422 AP_BILL_UNCLASSIFIED, PERIOD_CLOSED, PERIOD_HARD_LOCKED or \
                GL_MAPPING_NOT_CONFIGURED, leaving the bill as it was.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Exception resolved",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_APPROVABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "ACCEPT only: AP_BILL_UNCLASSIFIED, PERIOD_CLOSED, PERIOD_HARD_LOCKED or"
                    + " GL_MAPPING_NOT_CONFIGURED; the approval is rolled back",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> resolveMatchException(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Accept a price variance", value = """
                                                {"resolutionAction":"ACCEPT",
                                                 "reason":"Price increase agreed by phone"}
                                                """)))
                    @NonNull
                    @Valid
                    @RequestBody
                    VendorBillCommands.ResolveException request) {
        return ResponseEntity.ok(approvalService.resolveException(billId, request));
    }

    @PostMapping("/match-candidates/{candidateId}/select")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATE_SELECT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve", "accounting:ap:approve_over_limit"})
    @PreAuthorize("hasAnyAuthority('" + AccountingPermissions.AP_APPROVE + "', '"
            + AccountingPermissions.AP_APPROVE_OVER_LIMIT + "')")
    @Operation(
            operationId = "selectVendorBillMatchCandidate",
            summary = "Select Vendor Bill Match Candidate",
            description = """
                Picks one candidate bill of an ambiguous invoice match and resolves the candidate set. \
                Selection is matching only: the chosen bill keeps what the vendor billed (lines, total, the \
                invoice number and due date), gets its match evidence and moves to AWAITING_APPROVAL with the \
                caller as submittedBy; nothing approves it and nothing is posted.
                Use this tool after reviewing listVendorBillMatchCandidates; do not use \
                resolveVendorBillMatchException, which handles discrepancy exceptions on a single bill.
                Preconditions: the candidate exists and its set is unresolved; the chosen bill is \
                PENDING_RECEIPT_MATCH or MATCH_EXCEPTION.
                Required inputs: candidateId (UUID) as a path parameter; there is no request body.
                Emits ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATE_SELECT and writes a \
                VENDOR_BILL_MATCH_CANDIDATE_SELECT audit row.
                Returns 200 with the bill read, 403 FORBIDDEN without accounting:ap:approve or \
                accounting:ap:approve_over_limit, 404 AP_MATCH_CANDIDATE_NOT_FOUND, 409 \
                AP_MATCH_CANDIDATE_ALREADY_RESOLVED when someone else resolved the set, 409 \
                AP_BILL_NOT_APPROVABLE naming the chosen bill's status, and 409 AP_BILL_DUPLICATE when \
                another live bill already holds the invoice number.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Candidate selected; the bill awaits approval",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "AP_MATCH_CANDIDATE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_MATCH_CANDIDATE_ALREADY_RESOLVED, AP_BILL_NOT_APPROVABLE or AP_BILL_DUPLICATE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> selectMatchCandidate(
            @Parameter(description = "Match candidate identifier", example = "550e8400-e29b-41d4-a716-446655440030")
                    @NonNull
                    @PathVariable
                    UUID candidateId) {
        return ResponseEntity.ok(approvalService.selectCandidate(candidateId));
    }

    @PostMapping("/{billId}/void")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_VOID", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:reject", "accounting:ap:approve_over_limit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_REJECT + "') and hasAnyAuthority('"
            + AccountingPermissions.AP_APPROVE + "', '" + AccountingPermissions.AP_APPROVE_OVER_LIMIT + "')")
    @Operation(
            operationId = "voidApprovedVendorBill",
            summary = "Void Approved Vendor Bill",
            description = """
                Voids an APPROVED vendor bill while nothing is allocated to it: it moves to VOIDED and its \
                entry is reversed through the journal-entry reversal (linked both ways), dated today in \
                today's period, never back in the original period (AW42); 2100 is accrued again.
                Use this tool to undo an approval that should not stand; do not use rejectVendorBill, which \
                refuses a bill not yet approved, or resolveVendorBillMatchException with VOID, which voids a \
                bill still in MATCH_EXCEPTION. A bill with payments allocated is corrected with a vendor \
                credit note instead.
                Preconditions: the bill is APPROVED with no allocation. Needs accounting:ap:reject plus the \
                approval tier, accounting:ap:approve_over_limit until approval limits exist.
                Required inputs: billId (UUID) as a path parameter and reason (at least 10 characters); \
                overrideJustification (at least 10 characters) reverses into a CLOSED period with \
                accounting:period:override.
                Emits ACCOUNTING_VENDOR_BILL_VOID and writes a VENDOR_BILL_VOID audit row.
                Returns 200 with the bill read, its posting's reversalReference included; 400 \
                JUSTIFICATION_REQUIRED; 403 FORBIDDEN; 404 VENDOR_BILL_NOT_FOUND; 409 AP_BILL_NOT_VOIDABLE \
                when the bill is not APPROVED or has an allocation; 422 PERIOD_CLOSED or PERIOD_HARD_LOCKED \
                for today's period, leaving the bill as it was.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Voided; the entry is reversed",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_VOIDABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "PERIOD_CLOSED or PERIOD_HARD_LOCKED; nothing is voided",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> voidApproved(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Void",
                                                            value = "{\"reason\":\"Billed twice, the vendor"
                                                                    + " confirmed\"}")))
                    @NonNull
                    @Valid
                    @RequestBody
                    VendorBillCommands.VoidApproved request) {
        return ResponseEntity.ok(approvalService.voidApproved(billId, request));
    }

    @GetMapping("/stages")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_STAGES_VIEW", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "getVendorBillStageCounts",
            summary = "Get Vendor Bill Stage Counts",
            description = """
                Counts the vendor bills in each stage of Bills to pay, as of now: CHECK \
                (PENDING_RECEIPT_MATCH, MATCH_EXCEPTION, CURRENCY_HOLD), APPROVE (AWAITING_APPROVAL), PAY \
                (APPROVED with an open amount above 0) and DONE (APPROVED, paid in full, the last payment \
                dated in the current month). There is no due-date window, so bills without a due date count.
                Use this tool for the live counts of the review; use listVendorBillsByStage for the bills of \
                one stage, and listVendorBills for a due-date window.
                Preconditions: none beyond the caller holding accounting:ap:view.
                Required inputs: none.
                Emits an ACCOUNTING_VENDOR_BILL_STAGES_VIEW audit event; no state changes.
                Returns 200 with the four counts and asOf.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Stage counts",
            content = @Content(schema = @Schema(implementation = VendorBillReview.StageCounts.class)))
    public ResponseEntity<VendorBillReview.StageCounts> stageCounts() {
        return ResponseEntity.ok(approvalService.stageCounts());
    }

    @GetMapping("/by-stage")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_STAGE_LIST", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "listVendorBillsByStage",
            summary = "List Vendor Bills By Stage",
            description = """
                Lists the vendor bills of one stage of Bills to pay, each with its bill number, vendor name, \
                total, currency, bill and due dates, status, channel, submittedAt and open amount. The \
                server sets the order: CHECK and APPROVE oldest first, PAY by due date with bills without \
                one last, DONE newest paid first. There is no due-date window.
                Use this tool for the bills behind one count of getVendorBillStageCounts; use \
                getVendorBillById for one bill's full review read.
                Preconditions: none beyond the caller holding accounting:ap:view.
                Required inputs: stage (CHECK, APPROVE, PAY or DONE); page (from 0) and size (capped at \
                100) are optional.
                Emits an ACCOUNTING_VENDOR_BILL_STAGE_LIST audit event; no state changes.
                Returns 200 with a page of rows, and 400 VALIDATION_ERROR for an unknown stage.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(responseCode = "200", description = "One page of the stage")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: unknown stage",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<VendorBillReview.StageRow>> listByStage(
            @Parameter(description = "Stage", required = true, example = "APPROVE") @RequestParam @NonNull
                    VendorBillStage stage,
            @Parameter(description = "Page number, from 0", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, capped at 100", example = "20") @RequestParam(defaultValue = "20")
                    int size) {
        return ResponseEntity.ok(approvalService.listByStage(stage, page, size));
    }
}
