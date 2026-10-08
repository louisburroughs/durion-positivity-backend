package com.positivity.tax.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.dto.EvidenceRulesResponse;
import com.positivity.tax.internal.dto.PlausibilityCheckRequest;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse;
import com.positivity.tax.internal.security.TaxPermissions;
import com.positivity.tax.internal.service.TaxEvidenceRules;
import com.positivity.tax.internal.service.TaxPlausibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The receipt-evidence stubs (CAP:550 S32b): the evidence-rules read and the stated-tax plausibility
 * check. Both are configuration-driven stubs (AW48) answered with {@code source = STUB}; neither reads
 * tenant data, changes state or emits an event. Internal-only: no gateway route (ADR-0021).
 * <p>
 * Nothing here logs a request: the plausibility request carries a supplier's registration number, which
 * is never echoed or logged.
 */
@Validated
@RestController
@RequestMapping("/v1/tax")
@Tag(name = "Tax", description = "Tax calculation API")
public class TaxEvidenceController {

    private final TaxEvidenceRules evidenceRules;
    private final TaxPlausibilityService plausibilityService;

    public TaxEvidenceController(TaxEvidenceRules evidenceRules, TaxPlausibilityService plausibilityService) {
        this.evidenceRules = evidenceRules;
        this.plausibilityService = plausibilityService;
    }

    /**
     * The evidence rules a country configures, in effect on a date (AW53).
     *
     * @param countryCode two upper-case letters
     * @param asOf        optional date; defaults to today
     * @return the rules in effect
     */
    @GetMapping("/evidence-rules")
    @PreAuthorize("hasAuthority('" + TaxPermissions.RATES_VIEW + "')")
    @Operation(operationId = "getTaxEvidenceRules", summary = "List a country's evidence rules", description = """
                    Returns the evidence rules a country's tax profile configures that are in effect on a date: which
                    evidence a document type needs, such as the supplier's registration number, from which total.
                    Use this tool when a drawer receipt or a vendor bill must know whether it needs evidence for an
                    input-tax claim; do not use it to check a receipt's stated tax, which is checkTaxPlausibility
                    instead.
                    Preconditions: this endpoint is internal-only (ADR-0021/ADR-0014), reached by direct in-cluster
                    calls from pos-order and pos-accounting with the service authority, never through pos-api-gateway.
                    Required inputs: countryCode, two upper-case letters; asOf (ISO-8601 date) defaults to today.
                    No events are emitted and no state changes; every rule is configuration held for expert advice,
                    so source is always STUB, and amounts are in the returned currency.
                    A caller that cannot obtain the rules retries or holds, and never treats them as absent.
                    Returns 200 with an empty list for a country without a rule, and 400 VALIDATION_ERROR when
                    countryCode or asOf is missing or malformed.
                    """)
    @ApiResponse(responseCode = "200", description = "Rules in effect resolved (empty for a country without one)")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or malformed countryCode or asOf",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:rates:view"})
    public ResponseEntity<EvidenceRulesResponse> getEvidenceRules(
            @RequestParam
                    @NotBlank
                    @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be two upper-case letters")
                    String countryCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(evidenceRules.read(countryCode, asOf));
    }

    /**
     * Checks the tax stated on a receipt against its total (AW55).
     *
     * @param request the receipt
     * @return the outcome
     */
    @PostMapping("/plausibility-checks")
    @PreAuthorize("hasAuthority('" + TaxPermissions.RATES_VIEW + "')")
    @Operation(operationId = "checkTaxPlausibility", summary = "Check a receipt's stated tax", description = """
                    Checks the tax amounts stated on a receipt against its total, a bookkeeping control against typing
                    errors and not a tax rule, and answers whether the supplier's registration number is needed and
                    well formed.
                    Use this tool when a drawer receipt with stated tax is recorded; do not use it to compute tax,
                    which is calculateTax, or to read the evidence threshold, which is getTaxEvidenceRules instead.
                    Preconditions: this endpoint is internal-only (ADR-0021/ADR-0014), reached by direct in-cluster
                    calls from pos-order with the service authority, never through pos-api-gateway.
                    Required inputs: countryCode, regionCode, postalCode, currencyCode (the country profile's) and
                    receiptTotal (tax included, above zero); asOf defaults to today, and statedTaxes (each regime at
                    most once) and supplierRegistrationNumber are optional.
                    No events are emitted, no state changes and no tenant data is read; the supplier's number is never
                    echoed, logged or stored, and source is always STUB.
                    Each stated amount must be below receiptTotal, as must their sum, and at most receiptTotal times r
                    over one plus r rounded up to the minor unit plus a configured tolerance, where r is the regime's
                    row rate in the region, or 0 when the regime does not cover the region; a regime that covers the
                    region but has no row there is unrated, gets no rate bound, and makes the outcome RATE_UNAVAILABLE
                    when its amount is above zero.
                    Refusals come in this order, the first failing step answering with all its field errors: 400
                    VALIDATION_ERROR for a missing or malformed field, a negative amount or a repeated regime; 422 TAX_JURISDICTION_NOT_CONFIGURED for a country without a tax profile,
                    CURRENCY_NOT_SUPPORTED for another currency than the profile's, AMOUNT_PRECISION_EXCEEDS_CURRENCY
                    for an amount finer than the currency's minor unit, TAX_REGIME_NOT_DECLARED for a regime the country
                    does not declare, and TAX_AMOUNT_IMPLAUSIBLE with each offending amount's maximum, every 422 naming
                    its fields in fieldErrors.
                    """)
    @ApiResponse(responseCode = "200", description = "Stated tax plausible (PLAUSIBLE or RATE_UNAVAILABLE)")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or malformed field, negative amount, or a repeated regime (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The country has no tax profile (TAX_JURISDICTION_NOT_CONFIGURED), currencyCode is not the"
                    + " profile's currency (CURRENCY_NOT_SUPPORTED), an amount is finer than the currency's minor unit"
                    + " (AMOUNT_PRECISION_EXCEEDS_CURRENCY), a regime is not declared for the country"
                    + " (TAX_REGIME_NOT_DECLARED), or a stated amount is implausible (TAX_AMOUNT_IMPLAUSIBLE); fieldErrors"
                    + " name each offending field",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:rates:view"})
    public ResponseEntity<PlausibilityCheckResponse> checkPlausibility(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "A receipt's address, total and stated tax amounts, with the supplier's"
                                    + " registration number when one is printed",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Receipt with one stated amount",
                                                            value =
                                                                    "{\"countryCode\":\"ZZ\",\"regionCode\":\"Z1\",\"postalCode\":\"Z1Z 1Z1\",\"asOf\":\"2026-10-08\",\"currencyCode\":\"EUR\",\"receiptTotal\":150.00,\"statedTaxes\":[{\"regime\":\"REGIME_1\",\"amount\":19.50}]}")))
                    @Valid
                    @RequestBody
                    PlausibilityCheckRequest request) {
        return ResponseEntity.ok(plausibilityService.check(request));
    }
}
