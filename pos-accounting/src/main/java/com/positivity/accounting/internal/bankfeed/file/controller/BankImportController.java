package com.positivity.accounting.internal.bankfeed.file.controller;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportDiscardRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportMappingRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowUpdateRequest;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankfeed.file.service.BankImportService;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Statement-file imports (SPEC-manual-bank-reconciliation §4.3, §4.4, §6.1; story S3, #2302): the
 * phase-1 file adapter's surface. Upload, preview, mapping, row correction, commit and discard require
 * {@code accounting:reconciliation:adjust}; reads {@code accounting:reconciliation:view}; the download
 * of the retained raw file {@code accounting:reconciliation:approve} and is always audited.
 */
@RestController
@RequestMapping("/v1/accounting/bank-imports")
@Tag(name = "Bank Imports", description = "Bank statement file imports: upload, preview, mapping, correction, commit.")
@RequiredArgsConstructor
public class BankImportController {

    private static final String UPLOAD_DESCRIPTION = """
            Uploads a bank statement file (CSV) with its statement header for a reconcilable BANK_CASH account \
            and stages it for review: every row is parsed under the column mapping and sign convention, bad \
            rows are rejected one by one, and a preview shows opening + activity against closing. Nothing is \
            committed until the import is committed.
            Use this tool to bring a downloaded bank statement into reconciliation; do not use it for a \
            statement keyed by hand, use createBankStatement instead.
            Preconditions: the header must continue the account's previous committed statement unless a \
            gapAcknowledgement of at least 10 characters is given (required for the account's first statement, \
            refused on a contiguous one); the same file must not already be committed for the account.
            Required inputs: glAccountId, a UUIDv7 requestId, formatCode CSV, the file and statement \
            {startDate, endDate, openingBalance, closingBalance}.
            Emits an ACCOUNTING_BANK_IMPORT_CREATE event; a replay of the same requestId and payload returns \
            the original import with replayed=true.
            Returns 201 with status VALIDATED, or UPLOADED with mappingRequired when the columns need a \
            mapping; 422 when the file is unreadable (STATEMENT_IMPORT_FAILED), the account is not \
            reconcilable, the currency is not the account's, the window overlaps a committed statement or the \
            contiguity rule fails; 409 for a file already committed (naming the earlier importId and \
            statementId), a window already committed or a reused requestId; 400 when the request is malformed \
            or the acknowledgement is shorter than 10 characters.
            """;

    private final BankImportService bankImportService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createBankImport",
            summary = "Upload Bank Statement File",
            description = UPLOAD_DESCRIPTION,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "201",
            description = "Import staged (or replayed)",
            content = @Content(schema = @Schema(implementation = BankImportResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IMPORT_FILE_ALREADY_COMMITTED, STATEMENT_ALREADY_IMPORTED or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "STATEMENT_IMPORT_FAILED, ACCOUNT_NOT_RECONCILABLE, CURRENCY_NOT_SUPPORTED,"
                    + " STATEMENT_PERIOD_OVERLAP, STATEMENT_NOT_CONTIGUOUS or"
                    + " STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportResponse> createBankImport(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The file as base64 content, its statement header and parse options.",
                            required = true)
                    @RequestBody
                    @NonNull
                    BankImportCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bankImportService.create(request, null, null, null));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_CREATE", apiVersion = "1")
    @Operation(
            operationId = "uploadBankImportFile",
            summary = "Upload Bank Statement File (Multipart)",
            description = UPLOAD_DESCRIPTION
                    + "This form takes the bytes in a multipart part named file and the request in a JSON part"
                    + " named meta (application/json), whose content field is omitted.\n",
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "201",
            description = "Import staged (or replayed)",
            content = @Content(schema = @Schema(implementation = BankImportResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IMPORT_FILE_ALREADY_COMMITTED, STATEMENT_ALREADY_IMPORTED or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "STATEMENT_IMPORT_FAILED, ACCOUNT_NOT_RECONCILABLE, CURRENCY_NOT_SUPPORTED,"
                    + " STATEMENT_PERIOD_OVERLAP, STATEMENT_NOT_CONTIGUOUS or"
                    + " STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportResponse> uploadBankImportFile(
            @Parameter(description = "The statement file") @RequestPart("file") @NonNull MultipartFile file,
            @Parameter(description = "The request without content, as a JSON part") @RequestPart("meta") @NonNull
                    BankImportCreateRequest meta) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException unreadable) {
            throw new BankRecException(BankRecErrorCode.STATEMENT_IMPORT_FAILED, "The uploaded file could not be read");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bankImportService.create(meta, bytes, file.getOriginalFilename(), file.getContentType()));
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listBankImports",
            summary = "List Bank Imports",
            description = """
                    Lists statement-file imports, optionally for one GL bank account and one status, newest \
                    first and paginated, without their previews.
                    Use this tool to find an import still waiting for a mapping, a correction or a commit; do \
                    not use it for committed statements, use listBankStatements instead.
                    Preconditions: none beyond the view permission.
                    Inputs: optional glAccountId and status (UPLOADED, VALIDATED, COMMITTED, DISCARDED), page and \
                    size (at most 200).
                    No events are emitted beyond the ACCOUNTING_BANK_IMPORT_LIST audit event.
                    Returns 400 when the page bounds are invalid, and 403 without accounting:reconciliation:view.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Imports",
            content = @Content(schema = @Schema(implementation = BankImportListResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid page (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportListResponse> listBankImports(
            @Parameter(description = "GL bank account id") @RequestParam(required = false) @Nullable UUID glAccountId,
            @Parameter(description = "Import status") @RequestParam(required = false) @Nullable BankImportStatus status,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-200)", example = "20") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(bankImportService.list(glAccountId, status, page, size));
    }

    @GetMapping("/{importId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_GET", apiVersion = "1")
    @Operation(
            operationId = "getBankImport",
            summary = "Get Bank Import",
            description = """
                    Returns one statement-file import with its header, mapping, counts, status, outcome ids and \
                    the preview: the first five rows with their resulting sign and running balance, and opening + \
                    activity against closing for each statement it will create.
                    Use this tool to check an import before committing it; do not use it for the rows \
                    themselves, use listBankImportRows instead.
                    Preconditions: the import must belong to the caller's tenant.
                    Required inputs: importId (UUID) in the path.
                    No events are emitted beyond the ACCOUNTING_BANK_IMPORT_GET audit event.
                    Returns 404 when the import does not exist in the tenant, and 403 without \
                    accounting:reconciliation:view.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Import with preview",
            content = @Content(schema = @Schema(implementation = BankImportResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportResponse> getBankImport(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId) {
        return ResponseEntity.ok(bankImportService.get(importId));
    }

    @GetMapping("/{importId}/rows")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_VIEW})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_ROWS", apiVersion = "1")
    @Operation(
            operationId = "listBankImportRows",
            summary = "List Bank Import Rows",
            description = """
                    Lists an import's rows by row number with their raw cells, parsed values, status, rejection \
                    code and fingerprint collision, optionally in one status, paginated.
                    Use this tool to find the rejected, out-of-window or possibly duplicated rows to correct, \
                    skip or decide; do not use it for committed bank transactions, use listBankTransactions \
                    instead.
                    Preconditions: the import must belong to the caller's tenant.
                    Required inputs: importId (UUID) in the path; optional status (PARSED, REJECTED, CORRECTED, \
                    SKIPPED, POSSIBLE_DUPLICATE, OUT_OF_WINDOW, COMMITTED), page and size (at most 200).
                    No events are emitted beyond the ACCOUNTING_BANK_IMPORT_ROWS audit event.
                    Returns 404 when the import does not exist, 400 when the page bounds are invalid, and 403 \
                    without accounting:reconciliation:view.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Rows",
            content = @Content(schema = @Schema(implementation = BankImportRowListResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid page (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportRowListResponse> listBankImportRows(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId,
            @Parameter(description = "Row status") @RequestParam(required = false) @Nullable BankImportRowStatus status,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-200)", example = "50") @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(bankImportService.rows(importId, status, page, size));
    }

    @PutMapping("/{importId}/mapping")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_MAPPING_SET", apiVersion = "1")
    @Operation(
            operationId = "setBankImportMapping",
            summary = "Set Bank Import Mapping",
            description = """
                    Sets an import's column mapping, sign convention and parser options and parses every row \
                    again from the retained file; the import becomes VALIDATED once its columns are mapped. A \
                    corrected or widened header and split points may come with it.
                    Use this tool when the columns need a mapping or the preview does not tie because the sign \
                    convention is wrong; do not use it to fix one row, use updateBankImportRow instead.
                    Preconditions: the import must be UPLOADED or VALIDATED; earlier corrections, skips and \
                    duplicate decisions are discarded by the re-parse.
                    Required inputs: importId in the path and columnMapping; optional saveAsAccountDefault stores \
                    the mapping on the account's profile for the next import.
                    Emits an ACCOUNTING_BANK_IMPORT_MAPPING_SET event.
                    Returns 409 when the import is COMMITTED (IMPORT_ALREADY_COMMITTED) or DISCARDED \
                    (IMPORT_DISCARDED) or the version is stale; 422 when a changed header overlaps or breaks \
                    contiguity; 400 when the mapping or options are malformed; 404 for an unknown import.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Import re-parsed",
            content = @Content(schema = @Schema(implementation = BankImportResponse.class)))
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
            description = "BANK_IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IMPORT_ALREADY_COMMITTED, IMPORT_DISCARDED, STATEMENT_ALREADY_IMPORTED or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "STATEMENT_PERIOD_OVERLAP, STATEMENT_NOT_CONTIGUOUS or"
                    + " STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE after a header change",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportResponse> setBankImportMapping(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId,
            @RequestBody @NonNull BankImportMappingRequest request) {
        return ResponseEntity.ok(bankImportService.updateMapping(importId, request));
    }

    @PutMapping("/{importId}/rows/{rowId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_ROW_CORRECT", apiVersion = "1")
    @Operation(
            operationId = "updateBankImportRow",
            summary = "Correct Or Skip Bank Import Row",
            description = """
                    Corrects one row of an import (its raw cells are kept), skips it with a reason, or answers \
                    a possible duplicate (DISTINCT keeps it, DUPLICATE skips it).
                    Use this tool for rows the preview shows as REJECTED, OUT_OF_WINDOW or POSSIBLE_DUPLICATE; do \
                    not use it to remap every row, use setBankImportMapping instead.
                    Preconditions: the import must be UPLOADED or VALIDATED; a skipped row cannot be corrected; a \
                    duplicate decision only applies to a POSSIBLE_DUPLICATE row.
                    Required inputs: importId and rowId in the path and exactly one of correctedValues, skip=true \
                    with a reason of at least 10 characters, or duplicateDecision.
                    Emits an ACCOUNTING_BANK_IMPORT_ROW_CORRECT event.
                    Returns 409 when the import is COMMITTED or DISCARDED or the version is stale; 400 when the \
                    values are malformed or the reason is too short; 404 for an unknown import or row.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Row after the change",
            content = @Content(schema = @Schema(implementation = BankImportRowResponse.class)))
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
            description = "BANK_IMPORT_NOT_FOUND or BANK_IMPORT_ROW_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IMPORT_ALREADY_COMMITTED, IMPORT_DISCARDED or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportRowResponse> updateBankImportRow(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId,
            @Parameter(description = "Row id") @PathVariable @NonNull UUID rowId,
            @RequestBody @NonNull BankImportRowUpdateRequest request) {
        return ResponseEntity.ok(bankImportService.updateRow(importId, rowId, request));
    }

    @PostMapping("/{importId}/commit")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_COMMIT", apiVersion = "1")
    @Operation(
            operationId = "commitBankImport",
            summary = "Commit Bank Import",
            description = """
                    Commits a validated import in one transaction: its rows become bank transactions of one \
                    statement (one per split segment) through the same intake as a manual statement, the gap \
                    acknowledgement moves the account's reconciliation baseline, and the \
                    accounting.bankstatement.committed fact is queued.
                    Use this tool once the preview ties and every rejected or out-of-window row is corrected or \
                    skipped; do not use it to start a reconciliation, which is created from the statement.
                    Preconditions: status VALIDATED; no REJECTED rows; OUT_OF_WINDOW rows skipped; opening + \
                    activity equal to closing within one minor unit; POSSIBLE_DUPLICATE rows either decided in \
                    duplicateDecisions or committed as possible duplicates for later review.
                    Required inputs: importId in the path; optional duplicateDecisions [{rowNumber, decision}].
                    Emits an ACCOUNTING_BANK_IMPORT_COMMIT event; a second commit returns the same result.
                    Returns 422 IMPORT_NOT_COMMITTABLE with fieldErrors rows[n] and activityTotal, or the header \
                    codes when the previous statement changed since upload; 409 when the import is DISCARDED, the \
                    window or the file is already committed, or the version is stale; 404 for an unknown import.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Committed (or already committed)",
            content = @Content(schema = @Schema(implementation = BankImportCommitResponse.class)))
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
            description = "BANK_IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IMPORT_DISCARDED, STATEMENT_ALREADY_IMPORTED, IMPORT_FILE_ALREADY_COMMITTED or"
                    + " OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "IMPORT_NOT_COMMITTABLE, CURRENCY_NOT_SUPPORTED, STATEMENT_PERIOD_OVERLAP,"
                    + " STATEMENT_NOT_CONTIGUOUS or STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportCommitResponse> commitBankImport(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId,
            @RequestBody(required = false) @Nullable BankImportCommitRequest request) {
        return ResponseEntity.ok(bankImportService.commit(importId, request));
    }

    @PostMapping("/{importId}/discard")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_ADJUST})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_DISCARD", apiVersion = "1")
    @Operation(
            operationId = "discardBankImport",
            summary = "Discard Bank Import",
            description = """
                    Discards an import that will not be committed; the status becomes DISCARDED, which is \
                    terminal, and the raw file stays retained until its retention date.
                    Use this tool for a wrong or superseded upload; do not use it on a committed import, whose \
                    statement is corrected by an explicit supersede instead.
                    Preconditions: the import must be UPLOADED or VALIDATED.
                    Required inputs: importId in the path and a reason of at least 10 characters.
                    Emits an ACCOUNTING_BANK_IMPORT_DISCARD event.
                    Returns 409 when the import is COMMITTED or already DISCARDED or the version is stale; 400 \
                    when the reason is missing or too short; 404 for an unknown import.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "Discarded",
            content = @Content(schema = @Schema(implementation = BankImportResponse.class)))
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
            description = "BANK_IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IMPORT_ALREADY_COMMITTED, IMPORT_DISCARDED or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankImportResponse> discardBankImport(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId,
            @RequestBody @NonNull BankImportDiscardRequest request) {
        return ResponseEntity.ok(bankImportService.discard(importId, request));
    }

    @GetMapping(value = "/{importId}/file", produces = MediaType.ALL_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {AccountingPermissions.RECONCILIATION_APPROVE})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_BANK_IMPORT_FILE_READ", apiVersion = "1")
    @Operation(
            operationId = "downloadBankImportFile",
            summary = "Download Bank Import File",
            description = """
                    Returns the raw bytes of an import's uploaded file, as retained for audit, with its media \
                    type and file name; every download is recorded in the audit log.
                    Use this tool to review the evidence behind a committed statement; do not use it to read \
                    the parsed rows, use listBankImportRows instead.
                    Preconditions: the file must still be retained (the import carries retentionUntil).
                    Required inputs: importId (UUID) in the path.
                    Emits an ACCOUNTING_BANK_IMPORT_FILE_READ event and a BANK_IMPORT_FILE_READ audit row.
                    Returns 404 when the import does not exist (BANK_IMPORT_NOT_FOUND) or its file was purged \
                    after retention (BANK_IMPORT_FILE_NOT_FOUND), and 403 without accounting:reconciliation:approve.
                    """,
            tags = {"Bank Imports"})
    @ApiResponse(
            responseCode = "200",
            description = "The raw file",
            content =
                    @Content(
                            mediaType = "application/octet-stream",
                            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:approve",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "BANK_IMPORT_NOT_FOUND or BANK_IMPORT_FILE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> downloadBankImportFile(
            @Parameter(description = "Import id") @PathVariable @NonNull UUID importId) {
        BankImportService.ImportFile file = bankImportService.download(importId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(file.contentType()));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(file.fileName()).build());
        return new ResponseEntity<>(file.content(), headers, HttpStatus.OK);
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException invalid) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
