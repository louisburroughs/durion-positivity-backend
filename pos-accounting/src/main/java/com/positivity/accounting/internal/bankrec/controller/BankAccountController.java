package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.BankAccountListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankAccountProfileRequest;
import com.positivity.accounting.internal.bankrec.dto.BankAccountProfileResponse;
import com.positivity.accounting.internal.bankrec.service.BankAccountService;
import com.positivity.accounting.internal.security.AccountingPermissions;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bank accounts in reconciliation scope (SPEC-manual-bank-reconciliation §3.1, §4.1, §6.1, D5, D21;
 * story S2, #2301): the list with baseline, frontiers and counts, and the thin profile. The list
 * requires {@code accounting:reconciliation:view}; the profile requires {@code
 * accounting:reconciliation:adjust}.
 */
@RestController
@RequestMapping("/v1/accounting/bank-accounts")
@Tag(name = "Bank Accounts", description = "Reconcilable bank accounts and their bank-account profiles.")
@RequiredArgsConstructor
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_ACCOUNT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listBankAccounts",
            summary = "List Bank Accounts",
            description = """
                    Lists every active reconcilable BANK_CASH GL account with its bank-account profile, \
                    reconciliation baseline date, coverage and reconciled frontiers, and the counts of \
                    unexplained bank transactions and open outstanding items from the baseline on.
                    Use this tool to choose the account to reconcile and see how far it is covered; do not use \
                    it for the chart of accounts, use the GL account endpoints instead.
                    Preconditions: none beyond the view permission.
                    Inputs: optional page and size (at most 200).
                    No events are emitted beyond the ACCOUNTING_BANK_ACCOUNT_LIST audit event.
                    Returns 400 when the page bounds are invalid, and 403 without accounting:reconciliation:view.
                    """,
            tags = {"Bank Accounts"})
    @ApiResponse(
            responseCode = "200",
            description = "Bank accounts",
            content = @Content(schema = @Schema(implementation = BankAccountListResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid page (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankAccountListResponse> listBankAccounts(
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-200)", example = "20") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(bankAccountService.listBankAccounts(page, size));
    }

    @PutMapping("/{glAccountId}/profile")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_ACCOUNT_PROFILE_SET", apiVersion = "1")
    @Operation(
            operationId = "setBankAccountProfile",
            summary = "Set Bank Account Profile",
            description = """
                    Creates or updates the bank-account profile of a reconcilable BANK_CASH GL account: bank \
                    name, account mask, currency, default column mapping and statement cycle hint.
                    Use this tool to name the bank behind an account after its first statement created the \
                    profile; do not use it to move the reconciliation baseline, which only an acknowledged \
                    statement moves (the profile has no baseline field).
                    Preconditions: the account must be a reconcilable BANK_CASH account and the currency must be \
                    the ledger currency.
                    Required inputs: glAccountId in the path and currency; bankName (at most 100 characters), \
                    accountMask (at most 8), defaultColumnMapping and statementCycleHint are optional.
                    Emits an ACCOUNTING_BANK_ACCOUNT_PROFILE_SET event and writes an audit row.
                    Returns 422 when the account is not reconcilable or the currency is not the ledger currency, \
                    404 when the account does not exist, and 400 when a field is missing or too long.
                    """,
            tags = {"Bank Accounts"})
    @ApiResponse(
            responseCode = "200",
            description = "Profile created or updated",
            content = @Content(schema = @Schema(implementation = BankAccountProfileResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "GL_ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "ACCOUNT_NOT_RECONCILABLE or CURRENCY_NOT_SUPPORTED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankAccountProfileResponse> setBankAccountProfile(
            @Parameter(description = "GL bank account id") @PathVariable @NonNull UUID glAccountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The profile values; the reconciliation baseline is not among them.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Profile", value = """
                                                    {"bankName":"First National","accountMask":"4321","currency":"USD",
                                                     "statementCycleHint":"MONTHLY_EOM"}
                                                    """)))
                    @RequestBody
                    @NonNull
                    BankAccountProfileRequest request) {
        return ResponseEntity.ok(bankAccountService.setProfile(glAccountId, request));
    }
}
