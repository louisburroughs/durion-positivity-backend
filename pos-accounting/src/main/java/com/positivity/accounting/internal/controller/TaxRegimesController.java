package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.TaxRegimesResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.TaxRegimesService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The configured tax regimes front door (CAP:550 #2659; ADR-0071, AW59): pos-tax's regimes, with their regions and tax
 * types, for a country or the tenant's tax country, so the tax-registration panel offers regimes from configuration
 * instead of hard-coding them. People never call pos-tax directly. No location is taken or reached (ADR-0061 does not
 * apply).
 */
@RestController
@RequestMapping("/v1/accounting/tax-regimes")
@Validated
@Tag(name = "Accounting Tax Registrations", description = "Indirect-tax registrations the company holds")
public class TaxRegimesController {

    /** The shape pos-tax accepts; validated here so pos-tax's 400 is never the caller's (ADR-0017). */
    static final String COUNTRY_CODE = "^[A-Z]{2}$";

    private final TaxRegimesService taxRegimes;

    public TaxRegimesController(@NonNull TaxRegimesService taxRegimes) {
        this.taxRegimes = taxRegimes;
    }

    @GetMapping
    @EmitEvent(id = "ACCOUNTING_TAX_REGIMES_VIEW", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:tax_registration:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.TAX_REGISTRATION_VIEW + "')")
    @Operation(
            operationId = "listTaxRegimes",
            summary = "List Configured Tax Regimes",
            description = """
                    Returns the indirect-tax regimes configured for a country, each with the region codes it \
                    covers and the tax types registered and recovered under it, relayed from pos-tax's configuration.
                    Use this tool to offer the regime choices of a tax registration; do not use it to read the \
                    company's registrations, use listTaxRegistrations instead.
                    Preconditions: the caller holds accounting:tax_registration:view; the values are placeholders \
                    held for expert advice, so source is STUB.
                    Required inputs: none; countryCode (two upper-case letters) picks a country, otherwise the \
                    deployment's tax country (accounting.tax.country) applies.
                    Emits an ACCOUNTING_TAX_REGIMES_VIEW audit event; no state changes, and nothing is cached.
                    Returns 200 with an empty regimes list when the country configures none, 400 VALIDATION_ERROR for \
                    a malformed countryCode, and 503 SERVICE_UNAVAILABLE with Retry-After when pos-tax cannot answer.
                    """,
            tags = {"Accounting Tax Registrations"})
    @ApiResponse(
            responseCode = "200",
            description = "The configured regimes (empty when the country configures none)",
            content = @Content(schema = @Schema(implementation = TaxRegimesResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: countryCode is not two upper-case letters",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:tax_registration:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "SERVICE_UNAVAILABLE: pos-tax is unreachable, failing, refusing the read or answering"
                    + " unreadably (any 4xx or 5xx; the country is validated here first, so nothing is relayed);"
                    + " retry after the Retry-After interval",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TaxRegimesResponse> listTaxRegimes(
            @Parameter(
                            description = "Country code in ISO 3166-1 alpha-2 format; omit it for the tax country",
                            example = "ZZ")
                    @RequestParam(required = false)
                    @Pattern(regexp = COUNTRY_CODE, message = "countryCode must be two upper-case letters")
                    @Nullable
                    String countryCode) {
        return ResponseEntity.ok(taxRegimes.regimes(countryCode));
    }
}
