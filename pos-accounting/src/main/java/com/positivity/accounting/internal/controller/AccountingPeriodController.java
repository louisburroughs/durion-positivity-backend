package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountingPeriodReopenRequest;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * REST Controller for accounting period lifecycle management (Story B1,
 * decision D-7: monthly cadence, two-state OPEN → CLOSED lifecycle).
 * Handles listing periods and the close/reopen state transitions.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B1</a>
 */
@RestController
@RequestMapping("/v1/accounting/periods")
@Tag(
        name = "Accounting Periods",
        description = "Accounting period lifecycle: list periods, close a period, reopen a closed period.")
@RequiredArgsConstructor
@Validated
public class AccountingPeriodController {

    private static final Logger log = LoggerFactory.getLogger(AccountingPeriodController.class);

    private final AccountingPeriodService accountingPeriodService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:view"})
    @PreAuthorize("hasAuthority('accounting:period:view')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_LIST", apiVersion = "1")
    @Operation(
            summary = "List accounting periods",
            operationId = "listAccountingPeriods",
            description = "Lists all known accounting periods, most recent first (descending period code)."
                    + " Use this tool to review period statuses before closing or reopening a period."
                    + " Periods are auto-provisioned on first posting, so months never posted into may be absent;"
                    + " an absent month counts as OPEN for posting purposes."
                    + " No preconditions and no side effects; nothing is created by this call.",
            tags = {"Accounting Periods"})
    @ApiResponse(
            responseCode = "200",
            description = "Accounting periods listed, most recent first",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AccountingPeriodResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<AccountingPeriodResponse>> listAccountingPeriods() {
        log.info("List accounting periods");
        List<AccountingPeriodResponse> response = accountingPeriodService.listPeriods();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{periodCode}/close")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:close"})
    @PreAuthorize("hasAuthority('accounting:period:close')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_CLOSE", apiVersion = "1")
    @Operation(
            summary = "Close an accounting period",
            operationId = "closeAccountingPeriod",
            description = "Closes an OPEN accounting period (OPEN → CLOSED), recording its lifecycle status as"
                    + " CLOSED. Use this tool during month-end close after all journal entries for the month"
                    + " are posted; use reopenAccountingPeriod to reverse the transition."
                    + " Note: journal-entry posting paths do not yet reject postings dated into a CLOSED period;"
                    + " that enforcement (error code PERIOD_CLOSED) arrives with the period-enforcement story"
                    + " (parity-B2)."
                    + " Preconditions: the period must not already be CLOSED, and no DRAFT journal entries may"
                    + " be dated inside the period. A valid YYYY-MM period with no row whose month has already"
                    + " started is auto-provisioned and then closed."
                    + " The close is audit-logged with the acting user and emits ACCOUNTING_PERIOD_CLOSE."
                    + " Returns 409 PERIOD_ALREADY_CLOSED if the period is already closed, and 422"
                    + " PERIOD_HAS_DRAFT_ENTRIES listing the blocking draft journal entry IDs in fieldErrors —"
                    + " post or delete those entries before retrying.",
            tags = {"Accounting Periods"})
    @ApiResponse(
            responseCode = "200",
            description = "Period closed; the updated period is returned",
            content = @Content(schema = @Schema(implementation = AccountingPeriodResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "periodCode is not a valid YYYY-MM period code",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:close permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Period not found and its month has not started (PERIOD_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Period is already closed (PERIOD_ALREADY_CLOSED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DRAFT journal entries are dated inside the period (PERIOD_HAS_DRAFT_ENTRIES);"
                    + " fieldErrors lists the blocking draftJournalEntryIds",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountingPeriodResponse> closeAccountingPeriod(
            @Parameter(description = "Period code in YYYY-MM format", required = true, example = "2026-06")
                    @PathVariable
                    String periodCode) {
        log.info("Close accounting period {}", periodCode);
        AccountingPeriodResponse response = accountingPeriodService.closePeriod(periodCode);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{periodCode}/reopen")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:reopen"})
    @PreAuthorize("hasAuthority('accounting:period:reopen')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_REOPEN", apiVersion = "1")
    @Operation(
            summary = "Reopen a closed accounting period",
            operationId = "reopenAccountingPeriod",
            description = "Reopens a CLOSED accounting period (CLOSED → OPEN), resetting its lifecycle status"
                    + " to OPEN."
                    + " Use this tool only when late adjustments must be posted into an already-closed month;"
                    + " use closeAccountingPeriod to close it again afterwards."
                    + " Preconditions: a period row must exist for the code and be CLOSED."
                    + " Required input: a non-blank justification (max 500 characters) which is recorded on the"
                    + " period and in the audit trail with the acting user."
                    + " Emits ACCOUNTING_PERIOD_REOPEN. Returns 409 PERIOD_ALREADY_OPEN if the period is not"
                    + " closed, and 400 if the justification is missing or blank.",
            tags = {"Accounting Periods"})
    @ApiResponse(
            responseCode = "200",
            description = "Period reopened; the updated period is returned",
            content = @Content(schema = @Schema(implementation = AccountingPeriodResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "periodCode is not a valid YYYY-MM period code, or justification is missing or blank",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:reopen permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No period row exists for the code (PERIOD_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Period is already open (PERIOD_ALREADY_OPEN)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountingPeriodResponse> reopenAccountingPeriod(
            @Parameter(description = "Period code in YYYY-MM format", required = true, example = "2026-06")
                    @PathVariable
                    String periodCode,
            @Valid @RequestBody AccountingPeriodReopenRequest request) {
        log.info("Reopen accounting period {}", periodCode);
        AccountingPeriodResponse response =
                accountingPeriodService.reopenPeriod(periodCode, request.getJustification());
        return ResponseEntity.ok(response);
    }
}
