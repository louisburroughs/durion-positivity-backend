package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.RegisterFloatChangeRequest;
import com.positivity.accounting.internal.dto.RegisterFloatGoLiveRequest;
import com.positivity.accounting.internal.dto.RegisterFloatRelocationRequest;
import com.positivity.accounting.internal.dto.RegisterFloatResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.RegisterFloatService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * A register's change float (#2511; SPEC-accounting-workspace §4.6 "Float", §7.1 "Float"; AW16, AW17): the
 * once-only go-live float, Change float, and the relocation of a register to another location (#2571, AW32).
 * The register is pos-order's {@code terminalId} (AW31). No screen calls these yet; they are reachable through
 * the SDK.
 */
@RestController
@RequestMapping("/v1/accounting/registers/{registerId}/float")
@Validated
@Tag(name = "Accounting Register Float", description = "The change float kept in each register's drawer")
public class RegisterFloatController {

    private final RegisterFloatService registerFloatService;

    public RegisterFloatController(@NonNull RegisterFloatService registerFloatService) {
        this.registerFloatService = registerFloatService;
    }

    @PostMapping("/go-live")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:float:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.FLOAT_MANAGE + "')")
    @Operation(
            operationId = "establishGoLiveRegisterFloat",
            summary = "Establish Go-Live Register Float",
            description = """
                    Puts a register's change float on the books at go-live: posts Dr 1080 Register Float / \
                    Cr 3900 Opening Balance Equity for the amount, dated the go-live date, the 1080 line carrying \
                    the register and its location.
                    Use this tool once per register, when the shop starts using the system with cash already in \
                    the drawer; do not use changeRegisterFloat, which moves the difference to or from a bank \
                    account, and do not use createJournalEntry, which records no float.
                    Preconditions: caller holds accounting:float:manage; the go-live date falls in an OPEN \
                    period, with no override path (422 PERIOD_CLOSED or PERIOD_HARD_LOCKED otherwise); the \
                    register has no standing go-live and no standing float change (409 \
                    FLOAT_ALREADY_ESTABLISHED; correct a mistake by reversing the entry and running go-live \
                    again). Idempotent on requestId: a replay returns the first result with 200, another body \
                    with the same requestId is 409 IDEMPOTENCY_CONFLICT.
                    Required inputs: registerId (path, pos-order's terminalId), locationId, amount (more than \
                    zero), currencyCode (ISO 4217, the functional currency: 422 CURRENCY_NOT_SUPPORTED \
                    otherwise), goLiveDate, justification (at least 10 characters), requestId.
                    Emits an ACCOUNTING_REGISTER_FLOAT_GO_LIVE event, queues accounting.float.changed, writes \
                    an audit row naming the caller, and returns 201 with the previous and new amount, their \
                    currencyCode, and the journal entry id and number.
                    """,
            tags = {"Accounting Register Float"})
    @ApiResponse(responseCode = "201", description = "The go-live float was posted")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field, a currencyCode missing or not on the ISO 4217 list included"
                    + " (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:float:manage, or the location is outside the caller's location"
                    + " scope (LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "FLOAT_ALREADY_ESTABLISHED, IDEMPOTENCY_CONFLICT, or VERSION_CONFLICT (the register's first"
                    + " command raced another; retry)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "CURRENCY_NOT_SUPPORTED (currencyCode is not the functional currency, or not the currency the"
                    + " register's float is held in), PERIOD_CLOSED, PERIOD_HARD_LOCKED, GL_MAPPING_NOT_CONFIGURED,"
                    + " FLOAT_REGISTER_LOCATION_MISMATCH (the register belongs to another location), or"
                    + " FLOAT_DATE_BEFORE_RELOCATION (dated before the register's latest relocation)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_REGISTER_FLOAT_GO_LIVE", apiVersion = "1")
    public ResponseEntity<RegisterFloatResponse> establishGoLive(
            @Parameter(description = "The register: pos-order's terminalId", example = "T-1") @PathVariable
                    String registerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The go-live float and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = RegisterFloatGoLiveRequest.class),
                                            examples = @ExampleObject(name = "Drawer 1 at go-live", value = """
                                                                    {"locationId":"019a0000-0000-7000-8000-00000000a001",
                                                                     "amount":200.00,"currencyCode":"USD",
                                                                     "goLiveDate":"2026-10-01",
                                                                     "justification":"Counted float in drawer 1 at go-live",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000101"}
                                                                    """)))
                    @RequestBody
                    RegisterFloatGoLiveRequest request) {
        request.requireValid();
        // ADR-0061: @PreAuthorize answered "may this caller manage floats"; this answers "...at this location".
        SecurityContextHelper.locationScope().require(AccountingPermissions.FLOAT_MANAGE, request.locationId());
        return respond(registerFloatService.establishGoLive(registerId, request));
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:float:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.FLOAT_MANAGE + "')")
    @Operation(
            operationId = "changeRegisterFloat",
            summary = "Change Register Float",
            description = """
                    Sets a register's change float to a new amount. The difference posts against the chosen bank \
                    account: an increase Dr 1080 Register Float / Cr bank, a decrease Dr bank / Cr 1080. A \
                    register with no float yet starts at zero, so a register added after go-live is funded from \
                    the bank.
                    Use this tool when the shop decides a drawer should hold more or less change; do not use \
                    establishGoLiveRegisterFloat, which is once per register against opening balance equity, \
                    and do not use a drawer cash movement, which is not a float.
                    Preconditions: caller holds accounting:float:manage; bankGlAccountId is an active BANK_CASH \
                    account in functional currency (422 FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE); the new amount \
                    differs from the current one (422 FLOAT_AMOUNT_UNCHANGED); the effective date passes the \
                    period gate (a CLOSED period needs accounting:period:override and overrideJustification). \
                    Idempotent on requestId: a replay returns the first result with 200, another body with the \
                    same requestId is 409 IDEMPOTENCY_CONFLICT.
                    Required inputs: registerId (path), locationId, amount (zero or more), currencyCode (ISO \
                    4217, the functional currency: 422 CURRENCY_NOT_SUPPORTED otherwise), bankGlAccountId, \
                    justification (at least 10 characters), requestId; effectiveDate defaults to today in the \
                    tenant's accounting time zone.
                    Emits an ACCOUNTING_REGISTER_FLOAT_CHANGE event, queues accounting.float.changed, writes an \
                    audit row naming the caller, and returns 201 with the previous and new amount, their \
                    currencyCode, and the journal entry id and number.
                    """,
            tags = {"Accounting Register Float"})
    @ApiResponse(responseCode = "201", description = "The change was posted")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field, a currencyCode missing or not on the ISO 4217 list included"
                    + " (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:float:manage, or the location is outside the caller's location"
                    + " scope (LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT, or VERSION_CONFLICT (the register's first command raced another;"
                    + " retry)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "CURRENCY_NOT_SUPPORTED (currencyCode is not the functional currency, or not the currency the"
                    + " register's float is held in), FLOAT_AMOUNT_UNCHANGED, FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE,"
                    + " PERIOD_CLOSED, PERIOD_HARD_LOCKED,"
                    + " ACCOUNTING_TIME_ZONE_UNSET, GL_MAPPING_NOT_CONFIGURED, FLOAT_REGISTER_LOCATION_MISMATCH (the register"
                    + " belongs to another location) or FLOAT_DATE_BEFORE_RELOCATION (dated before the register's latest"
                    + " relocation)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_REGISTER_FLOAT_CHANGE", apiVersion = "1")
    public ResponseEntity<RegisterFloatResponse> changeFloat(
            @Parameter(description = "The register: pos-order's terminalId", example = "T-1") @PathVariable
                    String registerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The new float, the bank account and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = RegisterFloatChangeRequest.class),
                                            examples = @ExampleObject(name = "More change for drawer 1", value = """
                                                                    {"locationId":"019a0000-0000-7000-8000-00000000a001",
                                                                     "amount":300.00,"currencyCode":"USD",
                                                                     "bankGlAccountId":"019a0000-0000-7000-8000-00000000b000",
                                                                     "effectiveDate":"2026-10-15",
                                                                     "justification":"More change needed for the weekend rush",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000102"}
                                                                    """)))
                    @RequestBody
                    RegisterFloatChangeRequest request) {
        request.requireValid();
        // ADR-0061: @PreAuthorize answered "may this caller manage floats"; this answers "...at this location".
        SecurityContextHelper.locationScope().require(AccountingPermissions.FLOAT_MANAGE, request.locationId());
        return respond(registerFloatService.changeFloat(registerId, request));
    }

    @PostMapping("/relocation")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:float:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.FLOAT_MANAGE + "')")
    @Operation(
            operationId = "relocateRegisterFloat",
            summary = "Move a Register and Its Float to Another Location",
            description = """
                    Moves a register and its change float from fromLocationId to toLocationId, posting Dr 1080 \
                    Register Float at the destination / Cr 1080 at the origin for the current float on the \
                    effective date.
                    The float is unchanged, there is no bank or 3900 line, and a zero float moves without an \
                    entry (journalEntryId null), which fixes a go-live made under a mistyped location.
                    Use this tool for a register set up under the wrong location (ENTERED_IN_ERROR) or a drawer \
                    that moved (MOVED); do not use changeRegisterFloat, which changes the amount, and never \
                    reverse the relocation entry (409 FLOAT_RELOCATION_NOT_REVERSIBLE): move again instead.
                    Preconditions: the caller holds accounting:float:manage with both locations in scope (403 \
                    LOCATION_SCOPE_DENIED), and the float is held at fromLocationId (404 \
                    FLOAT_REGISTER_NOT_FOUND, 422 FLOAT_REGISTER_LOCATION_MISMATCH) and moves elsewhere (422 \
                    FLOAT_RELOCATION_SAME_LOCATION).
                    The register has no open pos-order session (422 FLOAT_REGISTER_SESSION_OPEN, referenceId \
                    names it) and its float is not negative (422 FLOAT_AMOUNT_NEGATIVE).
                    The effective date is not after today nor before the register's latest float entry (422 \
                    FLOAT_RELOCATION_DATE_INVALID), and passes the period gate (a CLOSED period needs \
                    accounting:period:override and overrideJustification).
                    Required inputs: registerId (path), fromLocationId, toLocationId, reason, justification (10 \
                    or more characters) and requestId, on which the command is idempotent (a replay returns the \
                    first result with 200, another body is 409 IDEMPOTENCY_CONFLICT); effectiveDate defaults to \
                    today in the accounting time zone.
                    Emits ACCOUNTING_REGISTER_FLOAT_RELOCATE, queues accounting.float.changed with kind \
                    RELOCATION, writes an audit row naming both locations and the reason, and returns 201.
                    """,
            tags = {"Accounting Register Float"})
    @ApiResponse(responseCode = "201", description = "The register was moved")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field, a missing reason or a justification under 10 characters"
                    + " (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:float:manage, or either location is outside the caller's location"
                    + " scope (LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "FLOAT_REGISTER_NOT_FOUND: the register has no float",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT or VERSION_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "FLOAT_REGISTER_LOCATION_MISMATCH (the register is not held at fromLocationId),"
                    + " FLOAT_RELOCATION_SAME_LOCATION, FLOAT_REGISTER_SESSION_OPEN (a pos-order session is open on the"
                    + " register; referenceId names it), FLOAT_RELOCATION_DATE_INVALID, FLOAT_AMOUNT_NEGATIVE,"
                    + " PERIOD_CLOSED, PERIOD_HARD_LOCKED, ACCOUNTING_TIME_ZONE_UNSET or GL_MAPPING_NOT_CONFIGURED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_REGISTER_FLOAT_RELOCATE", apiVersion = "1")
    public ResponseEntity<RegisterFloatResponse> relocate(
            @Parameter(description = "The register: pos-order's terminalId", example = "T-1") @PathVariable
                    String registerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Where the register moves from and to, and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = RegisterFloatRelocationRequest.class),
                                            examples = @ExampleObject(name = "Drawer 1 moved", value = """
                                                                    {"fromLocationId":"019a0000-0000-7000-8000-00000000a001",
                                                                     "toLocationId":"019a0000-0000-7000-8000-00000000a002",
                                                                     "reason":"MOVED","effectiveDate":"2026-10-15",
                                                                     "justification":"Drawer 1 moved to the new shop",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000103"}
                                                                    """)))
                    @RequestBody
                    RegisterFloatRelocationRequest request) {
        request.requireValid();
        // ADR-0061: @PreAuthorize answered "may this caller manage floats"; these answer "...at both locations".
        // The register's stored location is checked against fromLocationId under the row lock, in the service.
        SecurityContextHelper.locationScope().require(AccountingPermissions.FLOAT_MANAGE, request.fromLocationId());
        SecurityContextHelper.locationScope().require(AccountingPermissions.FLOAT_MANAGE, request.toLocationId());
        return respond(registerFloatService.relocate(registerId, request));
    }

    private static ResponseEntity<RegisterFloatResponse> respond(RegisterFloatService.Outcome outcome) {
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.response());
    }
}
