package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.ApApprovalPolicyService;
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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AP approval policy (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §5.5; AW4-AW6, AW33): the clerk and
 * automatic approval limits, the two separation-of-duties exception switches and the default AP terms, with their
 * change history. The actor of a change is the caller (ADR-0018); no body carries one. No location is taken or
 * reached: the policy is the tenant's (ADR-0061 does not apply).
 */
@RestController
@RequestMapping("/v1/accounting/ap-approval-policy")
@RequiredArgsConstructor
@Tag(name = "AP Approval Policy", description = "Approval limits and separation of duties for vendor bills")
@Validated
public class ApApprovalPolicyController {

    private final ApApprovalPolicyService policyService;

    @GetMapping
    @EmitEvent(id = "ACCOUNTING_AP_APPROVAL_POLICY_VIEW", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap_approval_policy:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_APPROVAL_POLICY_MANAGE + "')")
    @Operation(
            operationId = "getApApprovalPolicy",
            summary = "Get AP Approval Policy",
            description = """
                Returns the tenant's effective AP approval policy: clerkApprovalLimit and autoApprovalLimit (in the \
                functional currency, currencyCode), allowCreatorApproval, allowApproverPayment and defaultTerms, \
                with asOf and one page of the change history, newest first, each row naming who changed which \
                setting, their roles, the old and new value and the justification.
                A setting never written reads as its default: limits 0.00 (every bill goes to an over-limit \
                approver, nothing is approved automatically), both switches false, defaultTerms NET30; a stored \
                value that cannot be read shows as that default too.
                Use this tool to see who may approve which bills and the policy's history; use \
                setApApprovalPolicy instead to change it, and getVendorBillById for one bill's tier.
                Preconditions: the caller holds accounting:ap_approval_policy:manage.
                Required inputs: none; historyPage (from 0) and historySize (default 20, at most 100) are optional.
                Emits ACCOUNTING_AP_APPROVAL_POLICY_VIEW; nothing is changed.
                Returns 200 with the policy, 401 without a valid token, and 403 FORBIDDEN without the permission.
                """,
            tags = {"AP Approval Policy"})
    @ApiResponse(
            responseCode = "200",
            description = "The effective policy and one page of its history",
            content = @Content(schema = @Schema(implementation = ApApprovalPolicyResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:ap_approval_policy:manage",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ApApprovalPolicyResponse> getApApprovalPolicy(
            @Parameter(description = "History page, from 0", example = "0") @RequestParam(defaultValue = "0")
                    int historyPage,
            @Parameter(description = "History page size, at most 100", example = "20")
                    @RequestParam(defaultValue = "20")
                    int historySize) {
        return ResponseEntity.ok(policyService.get(historyPage, historySize));
    }

    @PutMapping
    @EmitEvent(id = "ACCOUNTING_AP_APPROVAL_POLICY_SET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap_approval_policy:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_APPROVAL_POLICY_MANAGE + "')")
    @Operation(
            operationId = "setApApprovalPolicy",
            summary = "Set AP Approval Policy",
            description = """
                Changes the settings the body gives, each optional (missing means unchanged): clerkApprovalLimit \
                and autoApprovalLimit (amounts >= 0 in the functional currency, the automatic limit never above \
                the clerk limit), allowCreatorApproval and allowApproverPayment (separation-of-duties exception \
                switches), and defaultTerms (DUE_ON_RECEIPT or NET1 to NET120).
                Only a setting whose effective value changes is written, with one AP_APPROVAL_POLICY_SET audit row \
                recording old and new value, the caller and their roles, the justification and the requestId; a \
                new limit applies to the next decision at once, waiting bills included, and approved bills are \
                never re-evaluated.
                Use this tool when a controller or general manager changes the approval limits, the switches or \
                the default terms; do not use it to read the policy, use getApApprovalPolicy instead.
                Preconditions: the caller holds accounting:ap_approval_policy:manage.
                Required inputs: justification (at least 10 characters) and requestId (a UUID generated once per \
                change); currencyCode (the functional currency) is required with either limit.
                Emits ACCOUNTING_AP_APPROVAL_POLICY_SET; the call is idempotent: a requestId already recorded, or \
                a body equal to the stored values, writes nothing and returns the current policy.
                Returns 200 with the GET body; 400 JUSTIFICATION_REQUIRED or VALIDATION_ERROR with fieldErrors \
                (a negative limit, an automatic limit above the clerk limit, terms outside the vocabulary, a \
                missing currencyCode or requestId); 401; 403 FORBIDDEN; 422 CURRENCY_NOT_SUPPORTED for a \
                currencyCode other than the functional currency, or AMOUNT_PRECISION_EXCEEDS_CURRENCY for a limit \
                finer than its minor unit; nothing is written on a refusal.
                """,
            tags = {"AP Approval Policy"})
    @ApiResponse(
            responseCode = "200",
            description = "The effective policy after the change, as the GET returns it",
            content = @Content(schema = @Schema(implementation = ApApprovalPolicyResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED, or VALIDATION_ERROR with fieldErrors naming each invalid field",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Not authenticated: no valid bearer token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:ap_approval_policy:manage",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "CURRENCY_NOT_SUPPORTED or AMOUNT_PRECISION_EXCEEDS_CURRENCY; nothing is written",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ApApprovalPolicyResponse> setApApprovalPolicy(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The settings to change, the justification and the request id.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Set the clerk limit", value = """
                                                {"clerkApprovalLimit":2500.00,"autoApprovalLimit":500.00,
                                                 "currencyCode":"USD",
                                                 "justification":"Clerks approve routine parts bills up to 2,500",
                                                 "requestId":"0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f"}
                                                """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ApApprovalPolicyRequest request) {
        return ResponseEntity.ok(policyService.set(request));
    }
}
