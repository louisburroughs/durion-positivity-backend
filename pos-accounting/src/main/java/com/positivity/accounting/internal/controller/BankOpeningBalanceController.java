package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.BankOpeningBalanceRequest;
import com.positivity.accounting.internal.dto.BankOpeningBalanceResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.BankOpeningBalanceService;
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
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A bank account's opening balance at cutover (#2572; SPEC-accounting-workspace OI-10; Accounting Domain ruling
 * 2026-10-07). A bank account carries no location, so there is no location gate. No screen calls this yet; it is
 * reachable through the SDK.
 */
@RestController
@RequestMapping("/v1/accounting/bank-accounts/{glAccountId}/opening-balance")
@Validated
@Tag(name = "Bank Accounts", description = "Reconcilable bank accounts and their bank-account profiles.")
public class BankOpeningBalanceController {

    private final BankOpeningBalanceService bankOpeningBalanceService;

    public BankOpeningBalanceController(@NonNull BankOpeningBalanceService bankOpeningBalanceService) {
        this.bankOpeningBalanceService = bankOpeningBalanceService;
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:create", "accounting:je:post"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_CREATE + "') and hasAuthority('"
            + AccountingPermissions.JE_POST + "')")
    @Operation(
            operationId = "establishBankOpeningBalance",
            summary = "Establish Bank Opening Balance",
            description = """
                    Puts a bank account's balance at cutover on the books, with the checks and deposits still in \
                    transit, against 3900 Opening Balance Equity. Posts one entry dated asOfDate: one bank line \
                    for statementBalance (a debit, a credit when overdrawn), one bank line per outstanding item \
                    carrying its reference and itemDate (an OUTSTANDING_CHECK credits the bank, a \
                    DEPOSIT_IN_TRANSIT debits it) and one 3900 line for the net, so the book balance is statement \
                    + deposits in transit − outstanding checks.
                    Use this tool once per bank account, when a shop's books move onto the platform; do not use \
                    createJournalEntry, which records no opening, and do not use it for cash not yet deposited \
                    at cutover (deposit it on or before asOfDate and list it as a deposit in transit); AR, AP, \
                    inventory and loan openings are out of scope.
                    Preconditions: caller holds accounting:je:create and accounting:je:post; the account exists \
                    (404 GL_ACCOUNT_NOT_FOUND) and is an active BANK_CASH account in functional currency (422 \
                    BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE); currencyCode is its currency (422 \
                    CURRENCY_NOT_SUPPORTED) and no amount is finer than its minor unit (422 \
                    AMOUNT_PRECISION_EXCEEDS_CURRENCY, every such field in fieldErrors); it has no \
                    standing opening (409 BANK_OPENING_BALANCE_ALREADY_ESTABLISHED; correct a mistake by reversing \
                    the entry, dated on or before asOfDate, and running the opening again); the balance at the end \
                    of asOfDate holds no line and no committed statement starts on or before it (422 \
                    BANK_OPENING_BALANCE_NOT_FIRST; later lines are allowed); a zero balance needs at least one \
                    item (422 BANK_OPENING_BALANCE_EMPTY); asOfDate is not after today in the tenant's accounting \
                    time zone and falls in an OPEN period, with no override path (422 PERIOD_CLOSED or \
                    PERIOD_HARD_LOCKED otherwise). Idempotent on requestId: a replay returns the first result with \
                    200, another body with the same requestId is 409 IDEMPOTENCY_CONFLICT.
                    Required inputs: glAccountId (path), asOfDate, statementBalance, currencyCode (ISO 4217, the \
                    code of every amount, ADR-0067), outstandingItems (type, reference, itemDate on or before \
                    asOfDate, amount more than zero; may be empty), justification (at least 10 characters), \
                    requestId.
                    Emits an ACCOUNTING_BANK_OPENING_BALANCE_ESTABLISH event, writes a BANK_OPENING_BALANCE_ESTABLISH \
                    audit row naming the caller, and returns 201 with the balances and their currencyCode, the \
                    journal entry id and number and each item's glLineId; the account's first bank statement then \
                    starts on asOfDate + 1 with opening balance = statementBalance and a gapAcknowledgement; \
                    registering each item's glLineId as an outstanding item there leaves an opening difference of \
                    0.00.
                    """,
            tags = {"Bank Accounts"})
    @ApiResponse(responseCode = "201", description = "The opening balance was posted")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field, named in fieldErrors: currencyCode missing or not an ISO 4217"
                    + " code, asOfDate after today, an itemDate after asOfDate, or an amount not more than zero or"
                    + " not below 10^14 (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:je:create or accounting:je:post",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No GL account with this id (GL_ACCOUNT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "BANK_OPENING_BALANCE_ALREADY_ESTABLISHED or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE, CURRENCY_NOT_SUPPORTED,"
                    + " AMOUNT_PRECISION_EXCEEDS_CURRENCY (every offending amount in fieldErrors),"
                    + " BANK_OPENING_BALANCE_NOT_FIRST, BANK_OPENING_BALANCE_EMPTY, PERIOD_CLOSED, PERIOD_HARD_LOCKED,"
                    + " ACCOUNTING_TIME_ZONE_UNSET or GL_MAPPING_NOT_CONFIGURED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_BANK_OPENING_BALANCE_ESTABLISH", apiVersion = "1")
    public ResponseEntity<BankOpeningBalanceResponse> establish(
            @Parameter(description = "The bank account (a BANK_CASH GL account)") @PathVariable UUID glAccountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The bank's balance at cutover, the items in transit and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = BankOpeningBalanceRequest.class),
                                            examples = @ExampleObject(name = "Cutover on October 31", value = """
                                                                    {"asOfDate":"2025-10-31",
                                                                     "statementBalance":10000.00,
                                                                     "currencyCode":"USD",
                                                                     "outstandingItems":[
                                                                      {"type":"OUTSTANDING_CHECK",
                                                                       "reference":"1043",
                                                                       "itemDate":"2025-10-28",
                                                                       "amount":450.00},
                                                                      {"type":"DEPOSIT_IN_TRANSIT",
                                                                       "reference":"DEP-1031",
                                                                       "itemDate":"2025-10-31",
                                                                       "amount":1200.00}],
                                                                     "justification":
                                                                      "Opening balance per the October statement",
                                                                     "requestId":
                                                                      "019a0000-0000-7000-8000-000000000201"}
                                                                    """)))
                    @RequestBody
                    BankOpeningBalanceRequest request) {
        request.requireValid();
        BankOpeningBalanceService.Outcome outcome = bankOpeningBalanceService.establish(glAccountId, request);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.response());
    }
}
