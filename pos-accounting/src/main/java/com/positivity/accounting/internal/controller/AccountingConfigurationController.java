package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountingTimeZoneResponse;
import com.positivity.accounting.internal.dto.AccountingTimeZoneUpdateRequest;
import com.positivity.accounting.internal.dto.AgedThresholdsRequest;
import com.positivity.accounting.internal.dto.AgedThresholdsResponse;
import com.positivity.accounting.internal.dto.CashSafetyCushionRequest;
import com.positivity.accounting.internal.dto.CashSafetyCushionResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.AccountingConfigurationService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant accounting configuration: the accounting-calendar time zone (#2558), the cash safety cushion and the aged-item
 * thresholds (#2515). The family is governed by the same authority as the hard-lock date ({@code
 * accounting:period:hard_lock}), because the time zone fixes where the tenant's periods are cut and the others are
 * management settings of the same weight.
 */
@RestController
@RequestMapping("/v1/accounting/configuration")
@Tag(
        name = "Accounting Configuration",
        description = "Tenant accounting configuration: the accounting-calendar time zone in which postings are dated"
                + " and periods are cut, the cash safety cushion, and the aged-item thresholds.")
@RequiredArgsConstructor
@Validated
public class AccountingConfigurationController {

    private static final Logger log = LoggerFactory.getLogger(AccountingConfigurationController.class);

    private final AccountingConfigurationService accountingConfigurationService;

    @PutMapping("/time-zone")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:hard_lock"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_HARD_LOCK + "')")
    @EmitEvent(id = "ACCOUNTING_CONFIGURATION_TIME_ZONE_SET", apiVersion = "1")
    @Operation(
            operationId = "setAccountingTimeZone",
            summary = "Set Accounting Time Zone",
            description = """
                    Sets the tenant's accounting-calendar time zone: the zone in which every settlement, payment \
                    application and posting is dated and every accounting period begins and ends. A tenant without \
                    a zone posts nothing; its facts are held SUSPENDED with ACCOUNTING_TIME_ZONE_UNSET.
                    Use this tool once, before the tenant's first period close, to set the zone of the legal \
                    entity's books; use setAccountingHardLockDate to lock history instead.
                    Preconditions: the tenant has never closed an accounting period and has no hard-lock date; \
                    after either the zone is fixed. Entries already posted keep their dates and periods.
                    Required inputs: timeZone, an IANA region id such as America/Chicago; fixed offsets such as \
                    +05:00 or Etc/GMT+5 and SystemV ids are rejected, UTC is accepted.
                    Emits an ACCOUNTING_CONFIGURATION_TIME_ZONE_SET event; the change is audited with the old \
                    zone, the new zone and the acting user, and setting the current zone again changes nothing.
                    Returns 400 INVALID_ACCOUNTING_TIME_ZONE for an invalid id and 409 ACCOUNTING_TIME_ZONE_LOCKED \
                    once a period was closed or a hard-lock date was set.
                    """,
            tags = {"Accounting Configuration"})
    @ApiResponse(
            responseCode = "200",
            description = "Time zone stored; the stored zone id is returned",
            content = @Content(schema = @Schema(implementation = AccountingTimeZoneResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "timeZone is missing or blank (ARGUMENT_NOT_VALID), or is not an IANA region id"
                    + " (INVALID_ACCOUNTING_TIME_ZONE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:hard_lock permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The tenant has closed a period or set a hard-lock date, so the zone can no longer change"
                    + " (ACCOUNTING_TIME_ZONE_LOCKED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountingTimeZoneResponse> setAccountingTimeZone(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The IANA region id of the tenant's accounting calendar.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Central time", value = """
                                                                    {"timeZone":"America/Chicago"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    AccountingTimeZoneUpdateRequest request) {
        log.info("Set accounting time zone");
        String stored = accountingConfigurationService.setAccountingTimeZone(request.getTimeZone());
        return ResponseEntity.ok(new AccountingTimeZoneResponse(stored));
    }

    @GetMapping("/cash-safety-cushion")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:hard_lock"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_HARD_LOCK + "')")
    @Operation(
            operationId = "getCashSafetyCushion",
            summary = "Get Cash Safety Cushion",
            description = """
                    Returns the tenant's cash safety cushion: a management threshold in the functional currency \
                    that the 30-day cash outlook compares its lowest projected balance against. The amount is \
                    null while the cushion is unset, and 0 is a real value.
                    Use this tool to read the current cushion before changing it; use setCashSafetyCushion \
                    instead to change or clear it, and do not use it to read cash balances.
                    Preconditions: the caller holds accounting:period:hard_lock.
                    Required inputs: none; there are no parameters and no request body.
                    Nothing is changed and no event is emitted.
                    Returns 200 with the amount (null when unset) and the functional currency code.
                    """,
            tags = {"Accounting Configuration"})
    @ApiResponse(
            responseCode = "200",
            description = "The cushion; amount is null while unset",
            content = @Content(schema = @Schema(implementation = CashSafetyCushionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:hard_lock permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CashSafetyCushionResponse> getCashSafetyCushion() {
        return ResponseEntity.ok(accountingConfigurationService.getCashSafetyCushion());
    }

    @PutMapping("/cash-safety-cushion")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:hard_lock"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_HARD_LOCK + "')")
    @EmitEvent(id = "ACCOUNTING_CONFIGURATION_CASH_SAFETY_CUSHION_SET", apiVersion = "1")
    @Operation(
            operationId = "setCashSafetyCushion",
            summary = "Set Cash Safety Cushion",
            description = """
                    Sets or clears the tenant's cash safety cushion: the amount, in the functional currency, \
                    below which the 30-day cash outlook warns that the lowest projected balance falls short. It \
                    is only a threshold: it is never posted, never reserved and never taken off the cash shown, \
                    and it refuses nothing.
                    Use this tool when Finance sets, raises, lowers or removes the cushion; use \
                    getCashSafetyCushion instead to read it, and do not use it to move money.
                    Preconditions: the caller holds accounting:period:hard_lock, and currencyCode is the tenant's \
                    functional currency.
                    Required inputs: amount (not negative, at the currency's minor-unit precision, 0 allowed; \
                    null clears the cushion), currencyCode (ISO 4217), a justification of at least 10 characters, \
                    and requestId, a UUID generated once per change.
                    Emits an ACCOUNTING_CONFIGURATION_CASH_SAFETY_CUSHION_SET event and audits the old and new \
                    amount with the justification and requestId; setting the current value again, or replaying a \
                    requestId, changes nothing.
                    Returns 400 VALIDATION_ERROR for a missing field, an unknown currency code, a negative amount \
                    or too many decimal places, 400 JUSTIFICATION_REQUIRED for a justification of 1 to 9 \
                    characters, 409 for a requestId already used with another body, and 422 \
                    CURRENCY_NOT_SUPPORTED for a currency other than the functional one.
                    """,
            tags = {"Accounting Configuration"})
    @ApiResponse(
            responseCode = "200",
            description = "Cushion stored (or cleared); the stored cushion is returned",
            content = @Content(schema = @Schema(implementation = CashSafetyCushionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "A field is missing or invalid (VALIDATION_ERROR), or the justification is shorter than"
                    + " 10 characters (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:hard_lock permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The requestId was already used with another body",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "currencyCode is not the tenant's functional currency (CURRENCY_NOT_SUPPORTED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CashSafetyCushionResponse> setCashSafetyCushion(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The cushion with its currency, the audit justification and the request id.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "One payroll", value = """
                                                                    {"amount":25000.00,"currencyCode":"USD",
                                                                     "justification":"One payroll of cushion after the Q3 review",
                                                                     "requestId":"019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6e"}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    CashSafetyCushionRequest request) {
        log.info("Set cash safety cushion");
        return ResponseEntity.ok(accountingConfigurationService.setCashSafetyCushion(request));
    }

    @GetMapping("/aged-thresholds")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:hard_lock"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_HARD_LOCK + "')")
    @Operation(
            operationId = "getAgedThresholds",
            summary = "Get Aged Thresholds",
            description = """
                    Returns the tenant's three aged-item thresholds, in calendar days on the accounting date: \
                    undepositedCashAgedDays (default 3), vendorCodUnappliedAgedDays (default 14) and \
                    deliveriesNotBilledDays (default 30). They decide when undeposited register cash, an unapplied \
                    vendor cash-on-delivery payment and a delivery with no approved bill become to-do items.
                    Use this tool to read the current thresholds before changing them; use setAgedThresholds \
                    instead to change them, and do not use it to list the aged items themselves.
                    Preconditions: the caller holds accounting:period:hard_lock.
                    Required inputs: none; there are no parameters and no request body.
                    Nothing is changed and no event is emitted.
                    Returns 200 with the three effective values; a value never written reads as its default.
                    """,
            tags = {"Accounting Configuration"})
    @ApiResponse(
            responseCode = "200",
            description = "The effective thresholds",
            content = @Content(schema = @Schema(implementation = AgedThresholdsResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:hard_lock permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AgedThresholdsResponse> getAgedThresholds() {
        return ResponseEntity.ok(accountingConfigurationService.getAgedThresholds());
    }

    @PutMapping("/aged-thresholds")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:period:hard_lock"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PERIOD_HARD_LOCK + "')")
    @EmitEvent(id = "ACCOUNTING_CONFIGURATION_AGED_THRESHOLDS_SET", apiVersion = "1")
    @Operation(
            operationId = "setAgedThresholds",
            summary = "Set Aged Thresholds",
            description = """
                    Replaces the tenant's three aged-item thresholds, in calendar days on the accounting date: \
                    undepositedCashAgedDays (1 to 30), vendorCodUnappliedAgedDays (1 to 90) and \
                    deliveriesNotBilledDays (7 to 120).
                    Use this tool when Finance changes how long undeposited register cash, an unapplied vendor \
                    cash-on-delivery payment or a delivery with no approved bill may wait before it is flagged; \
                    use getAgedThresholds instead to read them, and do not use it to act on an aged item.
                    Preconditions: the caller holds accounting:period:hard_lock.
                    Required inputs: all three day counts, a justification of at least 10 characters, and \
                    requestId, a UUID generated once per change.
                    Emits an ACCOUNTING_CONFIGURATION_AGED_THRESHOLDS_SET event and writes one audit row per \
                    threshold whose value changes (old and new value, justification, requestId); an unchanged \
                    threshold writes nothing, and a replayed requestId changes nothing.
                    Returns 400 VALIDATION_ERROR for a missing field or a value outside its range, 400 \
                    JUSTIFICATION_REQUIRED for a justification of 1 to 9 characters, and 409 for a requestId \
                    already used with another body.
                    """,
            tags = {"Accounting Configuration"})
    @ApiResponse(
            responseCode = "200",
            description = "Thresholds stored; the effective thresholds are returned",
            content = @Content(schema = @Schema(implementation = AgedThresholdsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "A field is missing or outside its range (VALIDATION_ERROR), or the justification is"
                    + " shorter than 10 characters (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:period:hard_lock permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The requestId was already used with another body",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AgedThresholdsResponse> setAgedThresholds(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The three thresholds with the audit justification and the request id.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Defaults", value = """
                                                                    {"undepositedCashAgedDays":3,
                                                                     "vendorCodUnappliedAgedDays":14,
                                                                     "deliveriesNotBilledDays":30,
                                                                     "justification":"Back to the standard thresholds",
                                                                     "requestId":"019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6f"}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    AgedThresholdsRequest request) {
        log.info("Set aged thresholds");
        return ResponseEntity.ok(accountingConfigurationService.setAgedThresholds(request));
    }
}
