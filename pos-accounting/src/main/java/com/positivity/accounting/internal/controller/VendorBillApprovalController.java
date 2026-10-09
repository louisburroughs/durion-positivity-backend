package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.accounting.internal.service.VendorBillPurchaseTax;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor-bill approval lifecycle (CAP:550 S12, #2509; SPEC-accounting-workspace §4.3, §5.2, §7.1; AW8, AW37-AW47):
 * submit, approve, reject, resolve a match exception, select a match candidate, void an approved bill or a receipt
 * placeholder, and the stage reads of Bills to pay. The actor of every command is the caller (ADR-0018); no body
 * carries one. The commands take no idempotency key: a replay finds the bill moved on and is answered 409.
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
                AWAITING_APPROVAL with the caller as submittedBy, and nothing is posted.
                From PENDING_RECEIPT_MATCH this is "send without a delivery match", for EDI bills only (a \
                goods-receipt bill needs its vendor invoice matched first, AW45); from MATCH_EXCEPTION it resolves \
                the exception for a person to approve.
                Use this tool when a clerk has checked a bill and wants it approved; do not use approveVendorBill, \
                which is the approver's decision, or resolveVendorBillMatchException, which accepts, corrects or \
                voids an exception directly.
                Preconditions: the bill is PENDING_RECEIPT_MATCH or MATCH_EXCEPTION (never CURRENCY_HOLD), no \
                ambiguous match naming it is open, a goods-receipt bill has its invoice matched, its total is not \
                0.00, and the vendor's gross equals net + tax within 0.01 per stated line (at most 0.05) unless a \
                difference is given (AW47).
                Required inputs: billId (UUID) as a path parameter and justification (at least 10 characters); \
                classification {debitClass, expenseMappingKey} is an optional proposal the approver may keep, and \
                difference {class FREIGHT|GOODS|EXPENSE|PRICE_DIFFERENCE, expenseMappingKey, justification} says \
                where an unreconciled gap posts.
                Emits ACCOUNTING_VENDOR_BILL_SUBMIT and writes a VENDOR_BILL_SUBMIT audit row; the command takes no \
                idempotency key, so a replay finds the bill AWAITING_APPROVAL and is answered 409 \
                AP_BILL_NOT_APPROVABLE.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM); 400 JUSTIFICATION_REQUIRED, VALIDATION_ERROR or ARGUMENT_NOT_VALID; \
                401 without a valid token; 403 FORBIDDEN without accounting:ap:approve or \
                accounting:ap:approve_over_limit; 404 VENDOR_BILL_NOT_FOUND; 409 AP_BILL_NOT_APPROVABLE naming the \
                status or an open ambiguous match, or AP_BILL_AWAITING_INVOICE; 422 AP_BILL_ZERO_TOTAL or \
                AP_BILL_TOTALS_UNRECONCILED, writing nothing.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Sent for approval",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED, VALIDATION_ERROR or ARGUMENT_NOT_VALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
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
            description = "AP_BILL_NOT_APPROVABLE (the bill's status, or an open ambiguous match) or"
                    + " AP_BILL_AWAITING_INVOICE (a goods-receipt bill without its matched invoice); a replay is"
                    + " refused this way too",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "AP_BILL_ZERO_TOTAL or AP_BILL_TOTALS_UNRECONCILED; nothing is written",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> submitForApproval(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The justification (at least 10 characters) and an optional classification"
                                    + " proposed to the approver.",
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
                    @Valid
                    @RequestBody
                    VendorBillCommands.@NonNull Submit request) {
        return ResponseEntity.ok(approvalService.submitForApproval(billId, request));
    }

    @PostMapping("/{billId}/approve")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_APPROVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve", "accounting:ap:approve_over_limit"})
    // Either approve permission passes the gate; the bill's tier is then checked against the current clerk limit
    // (CAP:550 S13, #2510): an OVER_LIMIT bill needs accounting:ap:approve_over_limit.
    @PreAuthorize("hasAnyAuthority('" + AccountingPermissions.AP_APPROVE + "', '"
            + AccountingPermissions.AP_APPROVE_OVER_LIMIT + "')")
    @Operation(
            operationId = "approveVendorBill",
            summary = "Approve Vendor Bill",
            description = """
                Approves a vendor bill in AWAITING_APPROVAL and posts it in the same transaction, so a bill is \
                approved if and only if it posted (AW37): accounts payable is credited the billed gross and the \
                debits follow the VENDOR_BILL posting category by class (receipt-matched lines 2100 at the received \
                price with the difference in 5050, unmatched goods 2100 at the stated net with the tax in 5050, \
                expenses the chosen EXPENSE_<CODE> key with the tax; for a tenant that recovers input tax, a \
                recoverable tax type whose regime is registered on the bill date debits TAX_RECOVERABLE_<regime> \
                instead, and a tax stated without its types recovers nothing).
                The vendor's gross - (net + tax) within 0.01 per stated line, at most 0.05, goes on the largest \
                debit as roundingAdjustment and a larger one where difference says (FREIGHT 5060, GOODS 2100, \
                EXPENSE its key, PRICE_DIFFERENCE 5050); the entry is dated on the bill date when that is on or \
                before today and its period is open, otherwise today.
                Use this tool for the approver's decision on a bill sent for approval; do not use \
                submitVendorBillForApproval, which only sends it, or resolveVendorBillMatchException with ACCEPT, \
                which approves a bill still in MATCH_EXCEPTION.
                Preconditions: in this order, the bill is AWAITING_APPROVAL (CURRENCY_HOLD bills never \
                are) and a goods-receipt bill has its invoice matched; a bill whose absolute total is over the \
                clerk limit (requiredTier OVER_LIMIT) needs accounting:ap:approve_over_limit, one within it \
                accounting:ap:approve; the caller did not create the bill unless the AP approval policy allows it \
                with a justification; then the content checks, the last of them the hold for tax on goods for \
                resale where the tax country's purchase-tax rules (read from pos-tax) hold such tax, and the \
                posting, whose first act is the self-assessed use-tax quote where those rules accrue it.
                Required inputs: billId (UUID) as a path parameter; justification (at least 10 characters), \
                classification {debitClass GOODS|EXPENSE, expenseMappingKey} (each field given wins over the one \
                proposed at submission), difference (as submitVendorBillForApproval takes it), taxByType \
                [{taxType, amount}] copied from the document (replacing the bill's stored tax by type), \
                overrideJustification (with accounting:period:override, to post into a CLOSED period) and \
                taxOnResaleOverrideJustification (10-1000 characters, accepting the bill's tax on goods for resale \
                for this bill only) are optional.
                Emits ACCOUNTING_VENDOR_BILL_APPROVE and writes a VENDOR_BILL_APPROVE audit row; a refused posting \
                writes one VENDOR_BILL_APPROVE_REFUSED row and changes nothing else, and a replayed approve finds \
                the bill APPROVED and is answered 409 AP_BILL_NOT_APPROVABLE.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM), its posting included; 400 JUSTIFICATION_REQUIRED (also a creator's \
                approval without one), VALIDATION_ERROR or ARGUMENT_NOT_VALID; 401 without a valid token; 403 \
                FORBIDDEN, AP_APPROVAL_LIMIT_EXCEEDED (nextAction names accounting:ap:approve_over_limit) or \
                AP_BILL_SELF_APPROVAL (the bill's creator, or the vendor's creator on its first bill, reason \
                VENDOR_CREATOR_FIRST_BILL), each limit or creator refusal audited as VENDOR_BILL_APPROVE_REFUSED; 404 \
                VENDOR_BILL_NOT_FOUND; 409 AP_BILL_NOT_APPROVABLE or AP_BILL_AWAITING_INVOICE; 422 \
                AP_BILL_UNCLASSIFIED (only when neither the classification, the proposal nor the vendor's AP \
                defaults give a class), AP_BILL_TAX_ON_RESALE_GOODS (audited as VENDOR_BILL_APPROVE_REFUSED), \
                AP_BILL_TOTALS_UNRECONCILED, AP_BILL_ZERO_TOTAL, AP_BILL_TAX_SPLIT_MISMATCH (taxByType not adding up \
                to the stated tax), AMOUNT_PRECISION_EXCEEDS_CURRENCY, PERIOD_CLOSED, PERIOD_HARD_LOCKED or \
                GL_MAPPING_NOT_CONFIGURED (guided: referenceId CATEGORY/KEY and nextAction), or, relayed from pos-tax \
                for the use-tax quote, TAX_JURISDICTION_NOT_CONFIGURED, CURRENCY_NOT_SUPPORTED or \
                TAX_CAPABILITY_UNSUPPORTED (a configuration to fix, not to retry); 503 SERVICE_UNAVAILABLE with \
                Retry-After when pos-tax cannot give the tax profile, the purchase-tax rules or the use-tax quote; \
                each leaving the bill as it was.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Approved and posted",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED, VALIDATION_ERROR or ARGUMENT_NOT_VALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN, AP_APPROVAL_LIMIT_EXCEEDED (over the clerk limit without"
                    + " accounting:ap:approve_over_limit) or AP_BILL_SELF_APPROVAL (the caller created the bill)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_APPROVABLE (a replay included) or AP_BILL_AWAITING_INVOICE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "AP_BILL_UNCLASSIFIED, AP_BILL_TAX_ON_RESALE_GOODS (the tax country's rules hold the bill's"
                    + " tax on goods for resale; override with taxOnResaleOverrideJustification),"
                    + " AP_BILL_TOTALS_UNRECONCILED, AP_BILL_ZERO_TOTAL, AP_BILL_TAX_SPLIT_MISMATCH,"
                    + " AMOUNT_PRECISION_EXCEEDS_CURRENCY, PERIOD_CLOSED, PERIOD_HARD_LOCKED,"
                    + " GL_MAPPING_NOT_CONFIGURED, or relayed from pos-tax for the use-tax quote"
                    + " TAX_JURISDICTION_NOT_CONFIGURED, CURRENCY_NOT_SUPPORTED or TAX_CAPABILITY_UNSUPPORTED (nothing"
                    + " written); the approval is rolled back",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "SERVICE_UNAVAILABLE: pos-tax cannot give the tax profile, the purchase-tax rules or the"
                    + " use-tax quote; nothing is written. Retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> approve(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The approver's optional justification (required, at least 10 characters,"
                                    + " when the creator approves under the policy's exception), the classification"
                                    + " the bill posts under, and an optional override justification for a CLOSED"
                                    + " period.",
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
                    @Valid
                    @RequestBody
                    VendorBillCommands.@NonNull Approve request) {
        // The override's upper bound is a request-shape check: 400 VALIDATION_ERROR naming the field (S43).
        VendorBillPurchaseTax.requireOverrideLength(request.taxOnResaleOverrideJustification());
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
                Rejects a vendor bill in AWAITING_APPROVAL: it moves to REJECTED, terminal, with the caller as \
                rejectedBy and the reason recorded.
                Nothing was posted, so nothing is reversed.
                Use this tool when the approver refuses a bill; do not use voidVendorBill, which voids a bill \
                already approved or a receipt placeholder, or resolveVendorBillMatchException with VOID, which voids \
                a bill still in MATCH_EXCEPTION.
                Preconditions: the bill is AWAITING_APPROVAL.
                Required inputs: billId (UUID) as a path parameter and reason (at least 10 characters).
                Emits ACCOUNTING_VENDOR_BILL_REJECT and writes a VENDOR_BILL_REJECT audit row; a replay finds the \
                bill REJECTED and is answered 409 AP_BILL_NOT_APPROVABLE.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM), 400 JUSTIFICATION_REQUIRED for a missing or short reason or \
                ARGUMENT_NOT_VALID for one over 1000 characters, 401 without a valid token, 403 FORBIDDEN without \
                accounting:ap:reject, 404 VENDOR_BILL_NOT_FOUND, and 409 AP_BILL_NOT_APPROVABLE naming the bill's \
                status.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Rejected",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED or ARGUMENT_NOT_VALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
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
            description = "AP_BILL_NOT_APPROVABLE, a replay included",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> reject(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The reason the bill is rejected, at least 10 characters.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reject",
                                                            value = "{\"reason\":\"Vendor billed a delivery we"
                                                                    + " refused\"}")))
                    @Valid
                    @RequestBody
                    VendorBillCommands.@NonNull Reject request) {
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
                Resolves a vendor bill in MATCH_EXCEPTION: ACCEPT is an approval and posts the bill exactly as \
                approveVendorBill does, CORRECT sends it back to PENDING_RECEIPT_MATCH as received (the billed lines \
                and total undone, the bill date the receipt date again, anything proposed cleared) writing no \
                approval or rejection field, and VOID voids it with the caller as rejectedBy (nothing was posted, so \
                nothing is reversed).
                Use this tool for a quantity, price, medium-confidence or totals exception on one bill; do not use \
                selectVendorBillMatchCandidate, which resolves an ambiguous match among several bills, or \
                submitVendorBillForApproval, which sends the bill to another person's approval.
                Preconditions: the bill is MATCH_EXCEPTION, and each action needs its own permission: ACCEPT and \
                CORRECT accounting:ap:approve or accounting:ap:approve_over_limit, VOID accounting:ap:reject; ACCEPT \
                is an approval and takes approveVendorBill's checks in its order (no open ambiguous match, a \
                matched invoice for a goods-receipt bill, the tier against the clerk limit, the caller not the \
                bill's creator with the reason as an exception's justification, the vendor's totals reconciled or \
                a difference).
                Required inputs: billId (UUID) as a path parameter, resolutionAction (ACCEPT, CORRECT or VOID) and \
                reason (at least 10 characters); ACCEPT also takes classification, difference, taxByType, \
                overrideJustification and taxOnResaleOverrideJustification as approveVendorBill does, with its \
                purchase-tax hold and use-tax quote, and an operatorId in the body is ignored because \
                the actor is the caller.
                Emits ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE and writes a \
                VENDOR_BILL_MATCH_EXCEPTION_RESOLVE audit row; a replay finds the bill moved on and is answered 409 \
                AP_BILL_NOT_APPROVABLE.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM); 400 VALIDATION_ERROR for an unknown action, JUSTIFICATION_REQUIRED \
                for a missing or short reason, or ARGUMENT_NOT_VALID; 401 without a valid token; 403 FORBIDDEN \
                without the action's permission, or for ACCEPT AP_APPROVAL_LIMIT_EXCEEDED or AP_BILL_SELF_APPROVAL \
                (also the vendor's creator on its first bill, reason VENDOR_CREATOR_FIRST_BILL; audited as \
                VENDOR_BILL_MATCH_EXCEPTION_RESOLVE_REFUSED); 404 VENDOR_BILL_NOT_FOUND; 409 AP_BILL_NOT_APPROVABLE or, for \
                ACCEPT, AP_BILL_AWAITING_INVOICE; for ACCEPT, 422 AP_BILL_UNCLASSIFIED (no class given, proposed or \
                defaulted for the vendor), AP_BILL_TAX_ON_RESALE_GOODS, AP_BILL_TOTALS_UNRECONCILED, \
                AP_BILL_TAX_SPLIT_MISMATCH, AMOUNT_PRECISION_EXCEEDS_CURRENCY, AP_BILL_ZERO_TOTAL, PERIOD_CLOSED, \
                PERIOD_HARD_LOCKED, GL_MAPPING_NOT_CONFIGURED or a relayed pos-tax TAX_JURISDICTION_NOT_CONFIGURED, \
                CURRENCY_NOT_SUPPORTED or TAX_CAPABILITY_UNSUPPORTED, and 503 SERVICE_UNAVAILABLE with Retry-After \
                when pos-tax cannot answer, leaving the bill as it was.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Exception resolved",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR, JUSTIFICATION_REQUIRED or ARGUMENT_NOT_VALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN; for ACCEPT also AP_APPROVAL_LIMIT_EXCEEDED or AP_BILL_SELF_APPROVAL",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_APPROVABLE (a replay included) or, for ACCEPT, AP_BILL_AWAITING_INVOICE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "ACCEPT only: AP_BILL_UNCLASSIFIED, AP_BILL_TAX_ON_RESALE_GOODS, AP_BILL_TOTALS_UNRECONCILED,"
                    + " AP_BILL_TAX_SPLIT_MISMATCH, AMOUNT_PRECISION_EXCEEDS_CURRENCY, AP_BILL_ZERO_TOTAL, PERIOD_CLOSED,"
                    + " PERIOD_HARD_LOCKED, GL_MAPPING_NOT_CONFIGURED, or relayed from pos-tax for the use-tax quote"
                    + " TAX_JURISDICTION_NOT_CONFIGURED, CURRENCY_NOT_SUPPORTED or TAX_CAPABILITY_UNSUPPORTED (nothing"
                    + " written); the approval is rolled back",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "ACCEPT only: SERVICE_UNAVAILABLE: pos-tax cannot give the tax profile, the purchase-tax"
                    + " rules or the use-tax quote; nothing is written. Retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> resolveMatchException(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The resolution action (ACCEPT, CORRECT or VOID) and its reason; ACCEPT may add a"
                                            + " classification and an override justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Accept a price variance", value = """
                                                {"resolutionAction":"ACCEPT",
                                                 "reason":"Price increase agreed by phone"}
                                                """)))
                    @Valid
                    @RequestBody
                    VendorBillCommands.@NonNull ResolveException request) {
        VendorBillPurchaseTax.requireOverrideLength(request.taxOnResaleOverrideJustification());
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
                Picks one candidate bill of an ambiguous invoice match and resolves the candidate set.
                Selection is matching only: the chosen bill keeps what the vendor billed (lines, total, the invoice \
                number, the invoice date as its bill date and the due date, AW46), gets its match evidence with the \
                receipt date and moves to AWAITING_APPROVAL with the caller as submittedBy, while a bill the match \
                had held in MATCH_EXCEPTION for this invoice returns to PENDING_RECEIPT_MATCH; nothing approves it \
                and nothing is posted.
                Use this tool after reviewing listVendorBillMatchCandidates or a bill read's openCandidates; do not \
                use resolveVendorBillMatchException, which handles discrepancy exceptions on a single bill.
                Preconditions: the candidate exists and its set is unresolved, the chosen bill is \
                PENDING_RECEIPT_MATCH or MATCH_EXCEPTION, and the candidate kept its invoice (one scored before \
                #2509 is refused; match the invoice again instead).
                Required inputs: candidateId (UUID) as a path parameter; there is no request body.
                Emits ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATE_SELECT and writes a VENDOR_BILL_MATCH_CANDIDATE_SELECT \
                audit row, plus a VENDOR_BILL_MATCH_CANDIDATE_RELEASE row for a bill released; a replay is answered \
                409 AP_MATCH_CANDIDATE_ALREADY_RESOLVED.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM), 401 without a valid token, 403 FORBIDDEN without \
                accounting:ap:approve or accounting:ap:approve_over_limit, 404 AP_MATCH_CANDIDATE_NOT_FOUND, 409 \
                AP_MATCH_CANDIDATE_ALREADY_RESOLVED when someone else resolved the set, 409 AP_BILL_NOT_APPROVABLE \
                naming the chosen bill's status, 409 AP_BILL_AWAITING_INVOICE for a candidate that kept no invoice, \
                and 409 AP_BILL_DUPLICATE when another live bill already holds the invoice number on the invoice \
                date.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Candidate selected; the bill awaits approval",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
            description = "AP_MATCH_CANDIDATE_ALREADY_RESOLVED (a replay included), AP_BILL_NOT_APPROVABLE,"
                    + " AP_BILL_AWAITING_INVOICE or AP_BILL_DUPLICATE",
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
            scopes = {"accounting:ap:reject"})
    // Every void needs accounting:ap:reject; an approved bill's also needs the approval tier, which the service
    // checks once it knows the bill's status (AW42, AW45).
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_REJECT + "')")
    @Operation(
            operationId = "voidVendorBill",
            summary = "Void Vendor Bill",
            description = """
                Voids a vendor bill: an APPROVED bill with nothing allocated moves to VOIDED and its entry is \
                reversed through the journal-entry reversal (linked both ways), dated today in today's period and \
                never back in the original period (AW42), so 2100 is accrued again; a goods-receipt bill in \
                PENDING_RECEIPT_MATCH that no vendor invoice will match moves to VOIDED and nothing is posted \
                (AW45), its receipt accrual staying in 2100 until the vendor's EDI bill classified GOODS clears it.
                Use this tool to undo an approval that should not stand or to close a receipt placeholder; do not \
                use rejectVendorBill, which refuses a bill not yet approved, or resolveVendorBillMatchException with \
                VOID, which voids a bill still in MATCH_EXCEPTION, and correct a bill with payments allocated with a \
                vendor credit note instead.
                Preconditions: every void needs accounting:ap:reject, and an approved bill's also either approve \
                permission and then its tier against the current clerk limit (an OVER_LIMIT bill needs \
                accounting:ap:approve_over_limit; the creator rule does not apply); only this void reverses a \
                bill's entry (the journal-entry reversal refuses one with 409 AP_BILL_ENTRY_NOT_REVERSIBLE).
                Required inputs: billId (UUID) as a path parameter and reason (at least 10 characters); \
                overrideJustification (at least 10 characters) reverses an approved bill into a CLOSED period with \
                accounting:period:override.
                Emits ACCOUNTING_VENDOR_BILL_VOID and writes a VENDOR_BILL_VOID audit row naming the action \
                (VOID_APPROVED or VOID_UNMATCHED); a replay finds the bill VOIDED and is answered 409 \
                AP_BILL_NOT_VOIDABLE.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM), an approved bill's posting with its reversalReference; 400 \
                JUSTIFICATION_REQUIRED or ARGUMENT_NOT_VALID; 401 without a valid token; 403 FORBIDDEN without \
                accounting:ap:reject, or for an approved bill without an approve permission, or \
                AP_APPROVAL_LIMIT_EXCEEDED over the clerk limit (audited as VENDOR_BILL_VOID_REFUSED); 404 \
                VENDOR_BILL_NOT_FOUND; \
                409 AP_BILL_NOT_VOIDABLE for any other status or an allocated bill; 422 PERIOD_CLOSED or \
                PERIOD_HARD_LOCKED for today's period, leaving the bill as it was.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Voided; an approved bill's entry is reversed, a receipt placeholder posts nothing",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED or ARGUMENT_NOT_VALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN, or AP_APPROVAL_LIMIT_EXCEEDED for an approved bill over the clerk limit",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_BILL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_BILL_NOT_VOIDABLE: not APPROVED nor a goods-receipt bill in PENDING_RECEIPT_MATCH,"
                    + " allocated, or a replay",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "PERIOD_CLOSED or PERIOD_HARD_LOCKED; nothing is voided",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> voidBill(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The reason the bill is voided (at least 10 characters) and, for an approved bill,"
                                            + " an optional override justification for a CLOSED period.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Void",
                                                            value = "{\"reason\":\"Billed twice, the vendor"
                                                                    + " confirmed\"}")))
                    @Valid
                    @RequestBody
                    VendorBillCommands.@NonNull VoidBill request) {
        return ResponseEntity.ok(approvalService.voidBill(billId, request));
    }

    @PutMapping("/{billId}/due-date")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_DUE_DATE_SET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_APPROVE + "')")
    @Operation(
            operationId = "setVendorBillDueDate",
            summary = "Set Vendor Bill Due Date",
            description = """
                Enters a vendor bill's real due date during approval review, as the vendor's document states it: \
                it is stored at the start of the day, replaces any estimate (estimates are never stored, AW11) and \
                is audited old to new as VENDOR_BILL_DUE_DATE_SET.
                A later invoice match or candidate selection whose invoice states a due date replaces it (the \
                vendor's document is the source); a date entered after the match stays.
                Use this tool when a clerk reads the due date off the bill; do not use it on an approved bill, \
                whose dates are locked, and use getVendorBillById instead to read the current one.
                Preconditions: the bill is PENDING_RECEIPT_MATCH, MATCH_EXCEPTION or AWAITING_APPROVAL; the date \
                is not tier-gated and separation of duties does not apply.
                Required inputs: billId (UUID) as a path parameter and dueDate (YYYY-MM-DD); justification is \
                optional, at least 10 characters when given, and an approvedBy or operatorId in the body is ignored \
                because the actor is the caller.
                Emits ACCOUNTING_VENDOR_BILL_DUE_DATE_SET; the same date again writes nothing.
                Returns 200 with the bill read (each actor with its display name: createdByName, \
                submittedByName, approvedByName and rejectedByName, null when not known or SYSTEM), 400 VALIDATION_ERROR or JUSTIFICATION_REQUIRED, 401 without a \
                valid token, 403 FORBIDDEN without accounting:ap:approve, 404 VENDOR_BILL_NOT_FOUND, and 409 \
                AP_BILL_NOT_APPROVABLE for any other status, CURRENCY_HOLD and APPROVED included.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Due date stored",
            content = @Content(schema = @Schema(implementation = VendorBillResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR (no dueDate) or JUSTIFICATION_REQUIRED (one under 10 characters)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
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
            description = "AP_BILL_NOT_APPROVABLE: the bill is not in approval review",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorBillResponse> setDueDate(
            @Parameter(description = BILL_ID, example = BILL_ID_EXAMPLE) @NonNull @PathVariable UUID billId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The due date the vendor's document states and an optional justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Due date from the paper invoice",
                                                            value = "{\"dueDate\":\"2026-11-07\"}")))
                    @Valid
                    @RequestBody
                    VendorBillCommands.@NonNull SetDueDate request) {
        return ResponseEntity.ok(approvalService.setDueDate(billId, request));
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
                Use this tool for the live counts of the review; do not use listVendorBillsByStage, which \
                lists the bills of one stage, or listVendorBills, which needs a due-date window.
                Preconditions: none beyond the caller holding accounting:ap:view.
                Required inputs: none.
                Emits an ACCOUNTING_VENDOR_BILL_STAGES_VIEW audit event; no state changes.
                Returns 200 with the four counts and asOf, 401 without a valid token, and 403 FORBIDDEN without \
                accounting:ap:view.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(
            responseCode = "200",
            description = "Stage counts",
            content = @Content(schema = @Schema(implementation = VendorBillReview.StageCounts.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                total, currency, bill and due dates, status, channel, submittedAt, open amount and, in review, the \
                requiredTier from the current clerk limit. The \
                server sets the order: CHECK and APPROVE oldest first, PAY by due date with bills without \
                one last, DONE newest paid first. There is no due-date window.
                Use this tool for the bills behind one count of getVendorBillStageCounts; use \
                getVendorBillById instead for one bill's full review read.
                Preconditions: none beyond the caller holding accounting:ap:view.
                Required inputs: stage (CHECK, APPROVE, PAY or DONE); page (from 0) and size (capped at \
                100) are optional.
                Emits an ACCOUNTING_VENDOR_BILL_STAGE_LIST audit event; no state changes.
                Returns 200 with a page of rows, 400 VALIDATION_ERROR for an unknown stage, 401 without a valid \
                token, and 403 FORBIDDEN without accounting:ap:view.
                """,
            tags = {"Vendor Bill API"})
    @ApiResponse(responseCode = "200", description = "One page of the stage")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: unknown stage",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN",
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
