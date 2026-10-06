package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountingTimeZoneResponse;
import com.positivity.accounting.internal.dto.AccountingTimeZoneUpdateRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant accounting configuration (#2558): the accounting-calendar time zone. It is governed by the same authority as
 * the hard-lock date ({@code accounting:period:hard_lock}), because both fix where the tenant's periods are cut.
 */
@RestController
@RequestMapping("/v1/accounting/configuration")
@Tag(
        name = "Accounting Configuration",
        description = "Tenant accounting configuration: the accounting-calendar time zone in which postings are dated"
                + " and periods are cut.")
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
}
