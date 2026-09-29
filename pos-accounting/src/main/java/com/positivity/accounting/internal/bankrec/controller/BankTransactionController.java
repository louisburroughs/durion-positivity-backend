package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.BankTransactionBatchResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionResponse;
import com.positivity.accounting.internal.bankrec.dto.DuplicateReviewRequest;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.service.BankTransactionService;
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
 * Bank transactions (SPEC-manual-bank-reconciliation §3.8, §4.5, §6.1; story S2, #2301): read an
 * account's rows, review possible duplicates, and exclude or restore rows. Reads require {@code
 * accounting:reconciliation:view}; duplicate review requires {@code accounting:reconciliation:adjust};
 * exclude and restore require {@code accounting:reconciliation:approve} (D3).
 */
@RestController
@RequestMapping("/v1/accounting/bank-transactions")
@Tag(
        name = "Bank Transactions",
        description = "Bank transactions as the bank reported them: reads, duplicate review, exclude and restore.")
@RequiredArgsConstructor
public class BankTransactionController {

    private static final String JUSTIFICATION_EXAMPLE = """
            {"justification":"Internal transfer recorded twice by the bank","version":0}
            """;

    private final BankTransactionService bankTransactionService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_TRANSACTION_LIST", apiVersion = "1")
    @Operation(
            operationId = "listBankTransactions",
            summary = "List Bank Transactions",
            description = """
                    Lists one GL bank account's bank transactions, filtered by status, date window and source, \
                    sorted by transaction date then id and paginated.
                    Use this tool to find the rows still to explain (unexplainedOnly=true returns UNMATCHED and \
                    POSSIBLE_DUPLICATE rows); do not use it for statement headers, use listBankStatements instead.
                    Preconditions: none beyond the view permission.
                    Required inputs: glAccountId; optional status, from, to, sourceKind, unexplainedOnly, page \
                    and size (at most 200).
                    No events are emitted beyond the ACCOUNTING_BANK_TRANSACTION_LIST audit event.
                    Returns 400 when glAccountId is missing, from is after to or the page bounds are invalid, \
                    and 403 without accounting:reconciliation:view.
                    """,
            tags = {"Bank Transactions"})
    @ApiResponse(
            responseCode = "200",
            description = "Transactions",
            content = @Content(schema = @Schema(implementation = BankTransactionListResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Missing account, invalid filter or page (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankTransactionListResponse> listBankTransactions(
            @Parameter(description = "GL bank account id (required)") @RequestParam(required = false) @Nullable
                    UUID glAccountId,
            @Parameter(description = "Row status") @RequestParam(required = false) @Nullable
                    BankTransactionStatus status,
            @Parameter(description = "Transactions dated on or after", example = "2026-09-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate from,
            @Parameter(description = "Transactions dated on or before", example = "2026-09-30")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate to,
            @Parameter(description = "Source of the rows") @RequestParam(required = false) @Nullable
                    SourceKind sourceKind,
            @Parameter(description = "Only UNMATCHED and POSSIBLE_DUPLICATE rows") @RequestParam(defaultValue = "false")
                    boolean unexplainedOnly,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-200)", example = "50") @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(bankTransactionService.listTransactions(
                glAccountId, status, from, to, sourceKind, unexplainedOnly, page, size));
    }

    @GetMapping("/{bankTransactionId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_TRANSACTION_GET", apiVersion = "1")
    @Operation(
            operationId = "getBankTransaction",
            summary = "Get Bank Transaction",
            description = """
                    Returns one bank transaction with everything the source delivered — provenance, retained \
                    descriptions, dedupe fingerprint, status and exclusion details.
                    Use this tool when you hold a bankTransactionId; do not use it to browse an account, use \
                    listBankTransactions instead.
                    Preconditions: the row must belong to the caller's tenant.
                    Required inputs: bankTransactionId (UUID) in the path.
                    No events are emitted beyond the ACCOUNTING_BANK_TRANSACTION_GET audit event.
                    Returns 404 when the row does not exist in the tenant, and 403 without \
                    accounting:reconciliation:view.
                    """,
            tags = {"Bank Transactions"})
    @ApiResponse(
            responseCode = "200",
            description = "Transaction",
            content = @Content(schema = @Schema(implementation = BankTransactionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankTransactionResponse> getBankTransaction(
            @Parameter(description = "Bank transaction id") @PathVariable @NonNull UUID bankTransactionId) {
        return ResponseEntity.ok(bankTransactionService.getTransaction(bankTransactionId));
    }

    @PostMapping("/{bankTransactionId}/duplicate-review")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_TRANSACTION_DUPLICATE_REVIEW", apiVersion = "1")
    @Operation(
            operationId = "reviewBankTransactionDuplicate",
            summary = "Review Possible Duplicate",
            description = """
                    Decides whether a POSSIBLE_DUPLICATE bank transaction is a real duplicate: DISTINCT returns it \
                    to UNMATCHED, DUPLICATE excludes it and records its original.
                    Use this tool for a row the intake flagged because its fingerprint matched another row; do not \
                    use it to exclude an ordinary row, use excludeBankTransaction instead.
                    Preconditions: the row must be POSSIBLE_DUPLICATE and, for DUPLICATE, name or have been flagged \
                    against another row on the same account.
                    Required inputs: decision (DISTINCT or DUPLICATE) and a justification of at least 10 \
                    characters; optional duplicateOfBankTransactionId and the version read.
                    Emits an ACCOUNTING_BANK_TRANSACTION_DUPLICATE_REVIEW event and writes an audit row.
                    Returns 409 when the row is not POSSIBLE_DUPLICATE or the version is stale, 404 when it does \
                    not exist, and 400 when the decision or justification is missing or too short.
                    """,
            tags = {"Bank Transactions"})
    @ApiResponse(
            responseCode = "200",
            description = "Reviewed row",
            content = @Content(schema = @Schema(implementation = BankTransactionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_LINE_INELIGIBLE or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankTransactionResponse> reviewBankTransactionDuplicate(
            @Parameter(description = "Bank transaction id") @PathVariable @NonNull UUID bankTransactionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The decision and its justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Distinct fees", value = """
                                                    {"decision":"DISTINCT","justification":"Two separate monthly fees charged on the same day","version":0}
                                                    """)))
                    @RequestBody
                    @NonNull
                    DuplicateReviewRequest request) {
        return ResponseEntity.ok(bankTransactionService.reviewDuplicate(bankTransactionId, request));
    }

    @PostMapping("/duplicate-review")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_TRANSACTION_DUPLICATE_REVIEW", apiVersion = "1")
    @Operation(
            operationId = "reviewBankTransactionDuplicates",
            summary = "Review Possible Duplicates In Bulk",
            description = """
                    Applies one duplicate-review decision to several POSSIBLE_DUPLICATE bank transactions at once, \
                    all or nothing.
                    Use this tool after an import flagged many rows the same way; do not use it for a single row, \
                    use reviewBankTransactionDuplicate instead.
                    Preconditions: every listed row must be POSSIBLE_DUPLICATE; for DUPLICATE each row uses the \
                    original it was flagged against unless duplicateOfBankTransactionId names one.
                    Required inputs: ids, decision and a justification of at least 10 characters.
                    Emits an ACCOUNTING_BANK_TRANSACTION_DUPLICATE_REVIEW event and writes one audit row per row.
                    Returns 409 when any row is not POSSIBLE_DUPLICATE, 404 when any id does not exist, and 400 \
                    when ids, the decision or the justification is missing or too short.
                    """,
            tags = {"Bank Transactions"})
    @ApiResponse(
            responseCode = "200",
            description = "Reviewed rows",
            content = @Content(schema = @Schema(implementation = BankTransactionBatchResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_LINE_INELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankTransactionBatchResponse> reviewBankTransactionDuplicates(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The rows, the decision and its justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Bulk distinct", value = """
                                                    {"ids":["01992b3c-4d5e-7f60-8a1b-2c3d4e5f6a01","01992b3c-4d5e-7f60-8a1b-2c3d4e5f6a02"],
                                                     "decision":"DISTINCT","justification":"Separate card payments of the same amount"}
                                                    """)))
                    @RequestBody
                    @NonNull
                    DuplicateReviewRequest request) {
        return ResponseEntity.ok(bankTransactionService.reviewDuplicates(request));
    }

    @PostMapping("/{bankTransactionId}/exclude")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_APPROVE})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_TRANSACTION_EXCLUDE", apiVersion = "1")
    @Operation(
            operationId = "excludeBankTransaction",
            summary = "Exclude Bank Transaction",
            description = """
                    Excludes an UNMATCHED bank transaction from reconciliation with a justification, so it leaves \
                    every sum and count; the row itself is kept as evidence.
                    Use this tool for a row the bank reported that should never be explained, such as a line the \
                    bank later reversed on the same statement; do not use it for a flagged possible duplicate, \
                    use reviewBankTransactionDuplicate instead.
                    Preconditions: the row must be UNMATCHED.
                    Required inputs: bankTransactionId in the path and a justification of at least 10 characters; \
                    optional version.
                    Emits an ACCOUNTING_BANK_TRANSACTION_EXCLUDE event and writes an audit row.
                    Returns 409 when the row is not UNMATCHED or the version is stale, 404 when it does not exist, \
                    403 without accounting:reconciliation:approve, and 400 when the justification is missing or \
                    too short.
                    """,
            tags = {"Bank Transactions"})
    @ApiResponse(
            responseCode = "200",
            description = "Excluded row",
            content = @Content(schema = @Schema(implementation = BankTransactionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:approve",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_LINE_INELIGIBLE or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankTransactionResponse> excludeBankTransaction(
            @Parameter(description = "Bank transaction id") @PathVariable @NonNull UUID bankTransactionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The justification and, optionally, the version read.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Exclude", value = JUSTIFICATION_EXAMPLE)))
                    @RequestBody
                    @NonNull
                    BankTransactionJustificationRequest request) {
        return ResponseEntity.ok(bankTransactionService.exclude(bankTransactionId, request));
    }

    @PostMapping("/{bankTransactionId}/restore")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_APPROVE})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_TRANSACTION_RESTORE", apiVersion = "1")
    @Operation(
            operationId = "restoreBankTransaction",
            summary = "Restore Bank Transaction",
            description = """
                    Returns an EXCLUDED bank transaction to UNMATCHED with a justification, so it counts as \
                    unexplained again.
                    Use this tool to undo an exclusion or a duplicate decision made in error; do not use it on a \
                    row a FINALIZED reconciliation covered, which is corrected by superseding that reconciliation \
                    instead.
                    Preconditions: the row must be EXCLUDED and no FINALIZED reconciliation on the account may \
                    cover its transaction date.
                    Required inputs: bankTransactionId in the path and a justification of at least 10 characters; \
                    optional version.
                    Emits an ACCOUNTING_BANK_TRANSACTION_RESTORE event and writes an audit row.
                    Returns 409 when the row is not EXCLUDED, a FINALIZED reconciliation covers it or the version \
                    is stale, 404 when it does not exist, 403 without accounting:reconciliation:approve, and 400 \
                    when the justification is missing or too short.
                    """,
            tags = {"Bank Transactions"})
    @ApiResponse(
            responseCode = "200",
            description = "Restored row",
            content = @Content(schema = @Schema(implementation = BankTransactionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:approve",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_LINE_INELIGIBLE or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankTransactionResponse> restoreBankTransaction(
            @Parameter(description = "Bank transaction id") @PathVariable @NonNull UUID bankTransactionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The justification and, optionally, the version read.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Restore", value = JUSTIFICATION_EXAMPLE)))
                    @RequestBody
                    @NonNull
                    BankTransactionJustificationRequest request) {
        return ResponseEntity.ok(bankTransactionService.restore(bankTransactionId, request));
    }
}
