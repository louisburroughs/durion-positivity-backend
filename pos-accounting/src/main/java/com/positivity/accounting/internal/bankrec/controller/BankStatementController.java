package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.service.BankStatementService;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bank statements (SPEC-manual-bank-reconciliation §4.3 item 3, §6.1; story S2, #2301): enter a
 * statement by hand through the intake port, and read statements with their gap acknowledgement and
 * reconciliation links. Reads require {@code accounting:reconciliation:view}; entry requires {@code
 * accounting:reconciliation:adjust}.
 */
@RestController
@RequestMapping("/v1/accounting/bank-statements")
@Tag(
        name = "Bank Statements",
        description = "Bank statement headers and manual statement entry for bank reconciliation.")
@RequiredArgsConstructor
public class BankStatementController {

    private final BankStatementService bankStatementService;

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_STATEMENT_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createBankStatement",
            summary = "Enter Bank Statement",
            description = """
                    Commits a bank statement keyed by hand — header, transactions and, when needed, a gap \
                    acknowledgement — for a reconcilable BANK_CASH account, through the same intake as a file.
                    Use this tool when the bank offers no statement download or to reconcile to a date; do not \
                    use it to correct a committed statement, which is an explicit supersede instead.
                    Preconditions: the statement must continue the account's previous statement (opening \
                    balance equals its closing balance, start date the day after its end) unless a \
                    gapAcknowledgement of at least 10 characters is given, which is required for the account's \
                    first statement, refused on a contiguous one and moves the account's reconciliation baseline.
                    Required inputs: glAccountId, a UUIDv7 requestId, statement {startDate, endDate, \
                    openingBalance, closingBalance} and transactions[] each with a date, a description and \
                    either signedAmount or one of debit/credit; startReconciliation optionally starts the \
                    statement's reconciliation in the same transaction (listed under reconciliations).
                    Emits an ACCOUNTING_BANK_STATEMENT_CREATE event and queues the \
                    accounting.bankstatement.committed fact; a replay of the same requestId and payload returns \
                    the original statement with replayed=true.
                    Returns 422 when the account is not reconcilable, the currency is not the account's, the \
                    window overlaps a committed statement, contiguity or the acknowledgement rule fails, a \
                    transaction lies outside the window or opening + activity differs from closing; 409 when the \
                    window is already committed or the requestId was used with another payload; and 400 when \
                    the request is malformed or a justification is shorter than 10 characters.
                    """,
            tags = {"Bank Statements"})
    @ApiResponse(
            responseCode = "201",
            description = "Statement committed (or replayed)",
            content = @Content(schema = @Schema(implementation = BankStatementResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request (VALIDATION_ERROR) or short acknowledgement (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "STATEMENT_ALREADY_IMPORTED or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "ACCOUNT_NOT_RECONCILABLE, CURRENCY_NOT_SUPPORTED, STATEMENT_PERIOD_OVERLAP,"
                    + " STATEMENT_NOT_CONTIGUOUS, STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE,"
                    + " STATEMENT_TRANSACTION_OUT_OF_WINDOW or STATEMENT_ACTIVITY_MISMATCH",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankStatementResponse> createBankStatement(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The statement header, its transactions and an optional gap acknowledgement.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "September statement", value = """
                                                    {"glAccountId":"5eed0acc-0000-4000-8000-000000001000",
                                                     "requestId":"01992b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b",
                                                     "statement":{"startDate":"2026-09-01","endDate":"2026-09-30",
                                                       "openingBalance":12345.67,"closingBalance":12830.67},
                                                     "transactions":[
                                                       {"date":"2026-09-02","credit":500.00,"description":"ACH DEPOSIT ACME","reference":"DEP-1"},
                                                       {"date":"2026-09-15","debit":15.00,"description":"MONTHLY FEE"}],
                                                     "gapAcknowledgement":"First statement reconciled on this account"}
                                                    """)))
                    @RequestBody
                    @NonNull
                    BankStatementCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bankStatementService.createManualStatement(request));
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_STATEMENT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listBankStatements",
            summary = "List Bank Statements",
            description = """
                    Lists bank statement headers, optionally for one GL bank account and for windows that meet a \
                    date range, sorted by start date and paginated.
                    Use this tool to see which periods an account's statements cover and which were acknowledged \
                    as gaps; do not use it for the transactions themselves, use listBankTransactions instead.
                    Preconditions: none beyond the view permission.
                    Inputs: optional glAccountId, from and to (ISO dates), page and size (at most 200).
                    No events are emitted beyond the ACCOUNTING_BANK_STATEMENT_LIST audit event.
                    Returns 400 when from is after to or the page bounds are invalid, and 403 without \
                    accounting:reconciliation:view.
                    """,
            tags = {"Bank Statements"})
    @ApiResponse(
            responseCode = "200",
            description = "Statements",
            content = @Content(schema = @Schema(implementation = BankStatementListResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter or page (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankStatementListResponse> listBankStatements(
            @Parameter(description = "GL bank account id") @RequestParam(required = false) @Nullable UUID glAccountId,
            @Parameter(description = "Windows ending on or after this date", example = "2026-01-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate from,
            @Parameter(description = "Windows starting on or before this date", example = "2026-12-31")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate to,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-200)", example = "20") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(bankStatementService.listStatements(glAccountId, from, to, page, size));
    }

    @GetMapping("/{statementId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_STATEMENT_GET", apiVersion = "1")
    @Operation(
            operationId = "getBankStatement",
            summary = "Get Bank Statement",
            description = """
                    Returns one bank statement header with its gap acknowledgement, transaction counts and the \
                    reconciliations that were started from it.
                    Use this tool when you hold a statementId; do not use it to browse an account's statements, \
                    use listBankStatements instead.
                    Preconditions: the statement must belong to the caller's tenant.
                    Required inputs: statementId (UUID) in the path.
                    No events are emitted beyond the ACCOUNTING_BANK_STATEMENT_GET audit event.
                    Returns 404 when the statement does not exist in the tenant, and 403 without \
                    accounting:reconciliation:view.
                    """,
            tags = {"Bank Statements"})
    @ApiResponse(
            responseCode = "200",
            description = "Statement",
            content = @Content(schema = @Schema(implementation = BankStatementResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_STATEMENT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankStatementResponse> getBankStatement(
            @Parameter(description = "Statement id") @PathVariable @NonNull UUID statementId) {
        return ResponseEntity.ok(bankStatementService.getStatement(statementId));
    }
}
