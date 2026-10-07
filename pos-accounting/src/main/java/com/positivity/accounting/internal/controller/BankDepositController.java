package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.DepositRecordRequest;
import com.positivity.accounting.internal.dto.DepositResponse;
import com.positivity.accounting.internal.dto.DepositReversalRequest;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.DepositService;
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
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
 * Bank deposits of drawer cash (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5, §5.1.1, §7.1; AW10): the
 * undeposited register sessions, Record bank deposit, a deposit, and Reverse deposit. Every session and deposit is
 * gated on its stored location (ADR-0061, location-scope.yaml). The Record bank deposit dialog is S20; no screen calls
 * Reverse deposit yet (it is reachable through the SDK, or as the generic entry reversal, which has the same effect).
 */
@RestController
@RequestMapping("/v1/accounting")
@Validated
@Tag(name = "Bank Deposits", description = "Drawer cash on its way to the bank: undeposited sessions and deposits.")
public class BankDepositController {

    private final DepositService depositService;

    public BankDepositController(@NonNull DepositService depositService) {
        this.depositService = depositService;
    }

    @GetMapping("/undeposited-sessions")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:deposit:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.DEPOSIT_CREATE + "')")
    @Operation(
            operationId = "listUndepositedSessions",
            summary = "List Undeposited Sessions",
            description = """
                    Lists the closed register sessions whose drawer cash has not reached the bank, oldest first, \
                    with their business references (register, location, close date and its age in days, bag \
                    numbers) and amounts: the bank drops (depositAmount), the CASH tender total (expectedCash) and \
                    the clearing net, the signed sum (debit positive) of the 1095 Register Cash Clearing lines the \
                    session's over/short and drawer movements posted. With one or more sessionId parameters it also \
                    returns selection: the totals, difference = depositAmount - expectedCash - clearingNet and the \
                    lines Record bank deposit would post (Dr bank, Cr 1090, Dr or Cr 1095), so a client never sums \
                    amounts itself. Nothing posts.
                    Use this tool to see what is waiting to be deposited and what a deposit of chosen sessions makes; \
                    do not use it for card, on-account or check amounts, which are never in a deposit.
                    Preconditions: caller holds accounting:deposit:create; a session outside the caller's location \
                    reach is not listed; a sessionId that names no listed session is 400 VALIDATION_ERROR; \
                    bankGlAccountId, when given, names a GL account (400 otherwise).
                    Required inputs: none; optional sessionId (repeatable) and bankGlAccountId, the account the \
                    preview's bank line names.
                    Emits an ACCOUNTING_UNDEPOSITED_SESSIONS_VIEW event and returns 200 with asOf, currencyCode, the \
                    sessions and the selection (null without sessionId).
                    """,
            tags = {"Bank Deposits"})
    @ApiResponse(responseCode = "200", description = "The undeposited sessions and, for a selection, its deposit")
    @ApiResponse(
            responseCode = "400",
            description = "A sessionId that names no undeposited session the caller may see, or an unknown"
                    + " bankGlAccountId (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:deposit:create",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_UNDEPOSITED_SESSIONS_VIEW", apiVersion = "1")
    public ResponseEntity<UndepositedSessionsResponse> listUndepositedSessions(
            @Parameter(description = "A session to select (repeatable): the response then carries its deposit")
                    @RequestParam(name = "sessionId", required = false)
                    @Nullable
                    List<UUID> sessionId,
            @Parameter(description = "The bank account the selection preview's bank line names")
                    @RequestParam(name = "bankGlAccountId", required = false)
                    @Nullable
                    UUID bankGlAccountId) {
        return ResponseEntity.ok(
                depositService.undeposited(sessionId == null ? List.of() : sessionId, bankGlAccountId));
    }

    @PostMapping("/deposits")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:deposit:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.DEPOSIT_CREATE + "')")
    @Operation(
            operationId = "recordBankDeposit",
            summary = "Record Bank Deposit",
            description = """
                    Records a bank deposit of whole closed register sessions' drawer cash in one step: posts one \
                    BANK_DEPOSIT entry dated depositDate, Dr the bank account by the sessions' bank drops (one line), \
                    Cr 1090 Undeposited Funds by their expected cash and Dr 1095 Register Cash Clearing when their \
                    clearing net is a credit (Cr when a debit) by its size, a zero line left out; the sessions become \
                    DEPOSITED. Every amount comes from the sessions (the server computes the deposit total). The \
                    single bank line is what bank reconciliation matches to the statement credit.
                    Use this tool when the cash of one or more closed drawers reached the bank; do not use \
                    createJournalEntry, and never add a balancing line yourself: a deposit that does not balance is \
                    refused. To correct a deposit, reverse it and record it again.
                    Preconditions: caller holds accounting:deposit:create and reaches every session's location (403 \
                    LOCATION_SCOPE_DENIED); currencyCode is the functional currency (422 CURRENCY_NOT_SUPPORTED); \
                    bankGlAccountId is an active, reconcilable BANK_CASH account in functional currency (422 \
                    DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE); every sessionId names a closed session waiting to be \
                    deposited (400 VALIDATION_ERROR when unknown, 409 DEPOSIT_SESSION_ALREADY_DEPOSITED when a \
                    deposit already took it); the selection holds bank drops (400 otherwise) and its drops equal its \
                    expected cash plus its clearing net (422 DEPOSIT_UNBALANCED naming the difference; list the \
                    sessions first to see it); no amount finer than the currency's minor unit (422 \
                    AMOUNT_PRECISION_EXCEEDS_CURRENCY); depositDate is in an OPEN period, or in a CLOSED one with \
                    accounting:period:override and overrideJustification (422 PERIOD_CLOSED otherwise), never \
                    before the hard-lock date (422 PERIOD_HARD_LOCKED). Idempotent on requestId: a replay returns \
                    the first result with 200 and replayed true, another body with the same requestId is 409 \
                    IDEMPOTENCY_CONFLICT.
                    Required inputs: bankGlAccountId, depositDate, currencyCode (ISO 4217, ADR-0067), sessionIds (1 \
                    to 200, distinct), requestId; optional depositSlipReference and overrideJustification.
                    Emits an ACCOUNTING_DEPOSIT_CREATE event, writes a BANK_DEPOSIT_RECORD audit row naming the \
                    caller, queues accounting.deposit.recorded (status RECORDED) and returns 201 with the deposit \
                    id, journalEntryNumber, slip reference, amounts and currencyCode, the sessions with their bag \
                    numbers and replayed false.
                    """,
            tags = {"Bank Deposits"})
    @ApiResponse(responseCode = "201", description = "The deposit was recorded")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result, replayed true")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field, named in fieldErrors: currencyCode missing or not ISO 4217, no"
                    + " or a repeated sessionId, a sessionId that names no undeposited session, or a selection"
                    + " with no bank drops (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:deposit:create, or a session's location is outside the caller's"
                    + " reach (LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DEPOSIT_SESSION_ALREADY_DEPOSITED or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DEPOSIT_UNBALANCED, DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE, CURRENCY_NOT_SUPPORTED,"
                    + " AMOUNT_PRECISION_EXCEEDS_CURRENCY, PERIOD_CLOSED, PERIOD_HARD_LOCKED,"
                    + " ACCOUNTING_TIME_ZONE_UNSET or GL_MAPPING_NOT_CONFIGURED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_DEPOSIT_CREATE", apiVersion = "1")
    public ResponseEntity<DepositResponse> recordBankDeposit(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The bank account, the date and the whole sessions deposited",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DepositRecordRequest.class),
                                            examples = @ExampleObject(name = "One drawer's cash", value = """
                                                                    {"bankGlAccountId":
                                                                      "019a0000-0000-7000-8000-00000000b000",
                                                                     "depositDate":"2026-10-08",
                                                                     "currencyCode":"USD",
                                                                     "sessionIds":[
                                                                      "019a0000-0000-7000-8000-00000000c001"],
                                                                     "requestId":
                                                                      "019a0000-0000-7000-8000-000000000301",
                                                                     "depositSlipReference":"DS-20261008-01"}
                                                                    """)))
                    @RequestBody
                    DepositRecordRequest request) {
        request.requireValid();
        DepositService.Outcome outcome = depositService.record(request);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.response());
    }

    @GetMapping("/deposits/{depositId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:deposit:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.DEPOSIT_CREATE + "')")
    @Operation(
            operationId = "getBankDeposit",
            summary = "Get Bank Deposit",
            description = """
                    Returns a bank deposit of drawer cash as it stands: RECORDED or REVERSED, its amounts and \
                    currencyCode, its entry number, the sessions it took with their bag numbers and, once reversed, \
                    the reversal's entry number, date and reason.
                    Use this tool to look up one deposit by id, e.g. after recording it; do not use it to list \
                    what is waiting to be deposited (listUndepositedSessions).
                    Preconditions: caller holds accounting:deposit:create and reaches every location the deposit \
                    took cash from (403 LOCATION_SCOPE_DENIED); the deposit exists (404 DEPOSIT_NOT_FOUND).
                    Required inputs: depositId (path).
                    Emits an ACCOUNTING_DEPOSIT_VIEW event and returns 200 with the deposit.
                    """,
            tags = {"Bank Deposits"})
    @ApiResponse(responseCode = "200", description = "The deposit")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:deposit:create, or a session's location is outside the caller's"
                    + " reach (LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No deposit with this id (DEPOSIT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_DEPOSIT_VIEW", apiVersion = "1")
    public ResponseEntity<DepositResponse> getBankDeposit(
            @Parameter(description = "The deposit") @PathVariable UUID depositId) {
        return ResponseEntity.ok(depositService.get(depositId));
    }

    @PostMapping("/deposits/{depositId}/reversal")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:deposit:reverse"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.DEPOSIT_REVERSE + "')")
    @Operation(
            operationId = "reverseBankDeposit",
            summary = "Reverse Bank Deposit",
            description = """
                    Reverses a bank deposit of drawer cash through the journal-entry reversal (ADR-0047): a reversing \
                    entry restores 1090 Undeposited Funds and 1095 Register Cash Clearing and takes the amount off \
                    the bank account, the deposit becomes REVERSED and its sessions wait to be deposited again. A \
                    reversed bank line matched in an approved bank reconciliation invalidates that reconciliation.
                    Use this tool to correct a wrong deposit, then record it again; deposits are never edited. \
                    Reversing the deposit's entry with reverseJournalEntry has the same effect.
                    Preconditions: caller holds accounting:deposit:reverse and reaches every location the deposit \
                    took cash from (403 LOCATION_SCOPE_DENIED); the deposit exists (404 DEPOSIT_NOT_FOUND) and is \
                    not already reversed (409 DEPOSIT_ALREADY_REVERSED); the reversal date (reversalDate, else the \
                    deposit's date if its period is open, else today) is in an OPEN period, or in a CLOSED one with \
                    accounting:period:override and overrideJustification (422 PERIOD_CLOSED otherwise), never \
                    before the hard-lock date (422 PERIOD_HARD_LOCKED). Idempotent on requestId: a replay returns \
                    the first result with replayed true, another body with the same requestId is 409 \
                    IDEMPOTENCY_CONFLICT.
                    Required inputs: depositId (path), reason (10 to 400 characters), requestId; optional \
                    reversalDate and overrideJustification.
                    Emits an ACCOUNTING_DEPOSIT_REVERSE event, writes a BANK_DEPOSIT_REVERSE audit row naming the \
                    caller, queues accounting.deposit.recorded (status REVERSED) and returns 200 with the deposit, \
                    its reversal entry number, date and reason.
                    """,
            tags = {"Bank Deposits"})
    @ApiResponse(responseCode = "200", description = "The deposit was reversed, or a replayed requestId's first result")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field, named in fieldErrors: a reason shorter than 10 or longer than 400"
                    + " characters, or no requestId (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:deposit:reverse, or a session's location is outside the caller's"
                    + " reach (LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No deposit with this id (DEPOSIT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DEPOSIT_ALREADY_REVERSED or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "PERIOD_CLOSED or PERIOD_HARD_LOCKED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_DEPOSIT_REVERSE", apiVersion = "1")
    public ResponseEntity<DepositResponse> reverseBankDeposit(
            @Parameter(description = "The deposit") @PathVariable UUID depositId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Why the deposit is reversed",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DepositReversalRequest.class),
                                            examples = @ExampleObject(name = "Wrong bank account", value = """
                                                                    {"reason":
                                                                      "Deposited into the wrong bank account",
                                                                     "requestId":
                                                                      "019a0000-0000-7000-8000-000000000302"}
                                                                    """)))
                    @RequestBody
                    DepositReversalRequest request) {
        request.requireValid();
        return ResponseEntity.ok(depositService.reverse(depositId, request).response());
    }
}
