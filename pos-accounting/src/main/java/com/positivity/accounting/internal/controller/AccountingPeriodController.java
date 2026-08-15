package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountingPeriodReopenRequest;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.dto.HardLockDateResponse;
import com.positivity.accounting.internal.dto.HardLockDateUpdateRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.service.AccountingConfigurationService;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for accounting period lifecycle management (Story B1,
 * decision D-7: monthly cadence, two-state OPEN → CLOSED lifecycle).
 * Handles listing periods and the close/reopen state transitions, plus the
 * org-level hard-lock date (Story B2, issue #944).
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan
 *      -
 *      Stories B1/B2</a>
 */
@RestController
@RequestMapping("/v1/accounting/periods")
@Tag(
        name = "Accounting Periods",
        description = "Accounting period lifecycle: list periods, close a period, reopen a closed period,"
                + " and manage the org-level hard-lock date.")
@RequiredArgsConstructor
@Validated
public class AccountingPeriodController {

    private static final Logger log = LoggerFactory.getLogger(AccountingPeriodController.class);

    private final AccountingPeriodService accountingPeriodService;
    private final AccountingConfigurationService accountingConfigurationService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_LIST", apiVersion = "1")
    @Operation(
            operationId = "listAccountingPeriods",
            summary = "List Accounting Periods",
            description = """
                    Lists all known accounting periods with their OPEN or CLOSED status, most recent first \
                    (descending period code).
                    Use this tool to review period statuses before closing or reopening a period; do not use \
                    closeAccountingPeriod or reopenAccountingPeriod, which perform the state transitions.
                    Preconditions: none; periods are auto-provisioned on first posting, so months never \
                    posted into may be absent, and an absent month counts as OPEN for posting purposes.
                    Required inputs: none; there are no parameters and no request body.
                    Emits an ACCOUNTING_PERIOD_LIST audit event; nothing is created or changed by this call.
                    Returns 200 with an empty list when no period has ever been provisioned.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_CLOSE + "')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_CLOSE", apiVersion = "1")
    @Operation(
            operationId = "closeAccountingPeriod",
            summary = "Close Accounting Period",
            description = """
                    Closes an OPEN accounting period (OPEN to CLOSED), after which posting paths reject \
                    entries dated inside it with PERIOD_CLOSED unless a permissioned override is supplied.
                    Use this tool during month-end close after all journal entries for the month are posted; \
                    do not use reopenAccountingPeriod, which reverses this transition for late adjustments.
                    Preconditions: the period must not already be CLOSED, and no DRAFT journal entries may be \
                    dated inside the period; a valid YYYY-MM code with no row whose month has already started \
                    is auto-provisioned and then closed.
                    Required inputs: periodCode (YYYY-MM) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_PERIOD_CLOSE event and audit-logs the close with the acting user.
                    Returns 409 PERIOD_ALREADY_CLOSED when the period is already closed, 404 PERIOD_NOT_FOUND \
                    when no row exists and the month has not started, and 422 PERIOD_HAS_DRAFT_ENTRIES listing \
                    the blocking draftJournalEntryIds in fieldErrors; post or delete those entries before \
                    retrying.
                    """,
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
                    @NonNull
                    String periodCode) {
        if (log.isInfoEnabled()) {
            log.info("Close accounting period {}", sanitizeForLog(periodCode));
        }
        AccountingPeriodResponse response = accountingPeriodService.closePeriod(periodCode);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{periodCode}/reopen")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:reopen"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_REOPEN + "')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_REOPEN", apiVersion = "1")
    @Operation(
            operationId = "reopenAccountingPeriod",
            summary = "Reopen Accounting Period",
            description = """
                    Reopens a CLOSED accounting period (CLOSED to OPEN), allowing entries to be posted into \
                    that month again.
                    Use this tool only when late adjustments must be posted into an already-closed month; use \
                    closeAccountingPeriod instead to close it again afterwards, and prefer a permissioned \
                    closed-period override on postJournalEntry for a one-off posting.
                    Preconditions: a period row must exist for the code and be CLOSED.
                    Required inputs: periodCode (YYYY-MM) as a path parameter and a non-blank justification \
                    (max 500 characters), recorded on the period and in the audit trail with the acting user.
                    Emits an ACCOUNTING_PERIOD_REOPEN event.
                    Returns 409 PERIOD_ALREADY_OPEN when the period is not closed, 404 PERIOD_NOT_FOUND when \
                    no period row exists for the code, and 400 when the justification is missing or blank.
                    """,
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
                    @NonNull
                    String periodCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Audit justification for reopening the closed period.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Late adjustment",
                                                            value =
                                                                    "{\"justification\":\"Post late utility accrual approved by controller\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    AccountingPeriodReopenRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Reopen accounting period {}", sanitizeForLog(periodCode));
        }
        AccountingPeriodResponse response =
                accountingPeriodService.reopenPeriod(periodCode, request.getJustification());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hard-lock")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_HARD_LOCK_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getAccountingHardLockDate",
            summary = "Get Accounting Hard-Lock Date",
            description = """
                    Returns the org-level hard-lock date; journal entries dated strictly before this date are \
                    permanently rejected with PERIOD_HARD_LOCKED and no override path, unlike a CLOSED period \
                    which accounting:period:override plus a justification can bypass.
                    Use this tool to check the current lock boundary before posting backdated entries; do not \
                    use setAccountingHardLockDate, which moves the lock, unless a change is intended.
                    Preconditions: none; the lock may be unset.
                    Required inputs: none; there are no parameters and no request body.
                    Emits an ACCOUNTING_PERIOD_HARD_LOCK_VIEW audit event; no state changes.
                    Returns 200 with hardLockDate null when no hard lock has been configured yet.
                    """,
            tags = {"Accounting Periods"})
    @ApiResponse(
            responseCode = "200",
            description = "Current hard-lock date returned; hardLockDate is null when no hard lock is set",
            content = @Content(schema = @Schema(implementation = HardLockDateResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<HardLockDateResponse> getHardLockDate() {
        log.debug("Get accounting hard-lock date");
        LocalDate hardLockDate =
                accountingConfigurationService.getHardLockDate().orElse(null);
        return ResponseEntity.ok(new HardLockDateResponse(hardLockDate));
    }

    @PutMapping("/hard-lock")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:hard_lock"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_HARD_LOCK + "')")
    @EmitEvent(id = "ACCOUNTING_PERIOD_HARD_LOCK_SET", apiVersion = "1")
    @Operation(
            operationId = "setAccountingHardLockDate",
            summary = "Set Accounting Hard-Lock Date",
            description = """
                    Sets the org-level hard-lock date; from then on, journal entries dated strictly before \
                    this date are permanently rejected with PERIOD_HARD_LOCKED and no override path, not even \
                    accounting:period:override, which only covers CLOSED periods.
                    Use this tool after statutory filings or audits to make history immutable; use \
                    closeAccountingPeriod for the reversible month-end close instead.
                    Preconditions: the new date must be on or after the currently stored hard-lock date; the \
                    lock is monotonic-forward-only and effectively irreversible, so set it deliberately.
                    Required inputs: hardLockDate (ISO date) and a non-blank justification (max 500 \
                    characters), recorded in the audit trail with the acting user.
                    Emits an ACCOUNTING_PERIOD_HARD_LOCK_SET event and returns the stored hard-lock date.
                    Returns 422 HARD_LOCK_DATE_REGRESSION when the date would move backward, and 400 when the \
                    date or justification is missing or blank.
                    """,
            tags = {"Accounting Periods"})
    @ApiResponse(
            responseCode = "200",
            description = "Hard-lock date updated; the stored value is returned",
            content = @Content(schema = @Schema(implementation = HardLockDateResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "hardLockDate is missing, or justification is missing, blank, or over 500 characters"
                    + " (ARGUMENT_NOT_VALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:hard_lock permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Requested date is before the currently stored hard-lock date"
                    + " (HARD_LOCK_DATE_REGRESSION) — the hard lock only moves forward",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<HardLockDateResponse> setHardLockDate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "New hard-lock date with the audit justification for moving it forward.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Lock after audit", value = """
                                                                    {"hardLockDate":"2026-01-01",
                                                                     "justification":"FY2025 audit finalized; history locked"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    HardLockDateUpdateRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Set accounting hard-lock date to {}", sanitizeForLog(request.getHardLockDate()));
        }
        LocalDate stored =
                accountingConfigurationService.setHardLockDate(request.getHardLockDate(), request.getJustification());
        return ResponseEntity.ok(new HardLockDateResponse(stored));
    }

    private static String sanitizeForLog(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString().replace('\n', '_').replace('\r', '_');
    }
}
