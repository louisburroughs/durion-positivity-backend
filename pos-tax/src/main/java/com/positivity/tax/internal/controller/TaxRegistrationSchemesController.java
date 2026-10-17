package com.positivity.tax.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.dto.TaxRegistrationSchemesResponse;
import com.positivity.tax.internal.security.TaxPermissions;
import com.positivity.tax.internal.service.TaxRegistrationSchemes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The registration-schemes stub (CAP:550 #2623, AW48): the closed vocabulary of tax-registration schemes, per
 * country, answered with {@code source = STUB}. It reads no tenant data, changes no state and emits no event.
 * Internal-only: pos-supplier validates vendor entries against it and relays it through its own front door
 * (ADR-0071, AW59), and there is no gateway route (ADR-0021).
 */
@Validated
@RestController
@RequestMapping("/v1/tax")
@Tag(name = "Tax", description = "Tax calculation API")
public class TaxRegistrationSchemesController {

    private final TaxRegistrationSchemes registrationSchemes;

    public TaxRegistrationSchemesController(TaxRegistrationSchemes registrationSchemes) {
        this.registrationSchemes = registrationSchemes;
    }

    /**
     * The tax-registration schemes a country configures, or every configured country's.
     *
     * @param countryCode two upper-case letters, optional
     * @return the schemes
     */
    @GetMapping("/registration-schemes")
    @PreAuthorize("hasAuthority('" + TaxPermissions.RATES_VIEW + "')")
    @Operation(
            operationId = "getTaxRegistrationSchemes",
            summary = "List the tax-registration schemes, per country",
            description = """
                    Returns the closed vocabulary of tax-registration schemes: per country, each scheme's code,
                    label, holder type (BUSINESS or PERSON) and whether a registration under it belongs to one
                    region, with the allowed regions. Codes are unique across countries, so a code alone
                    identifies a scheme.
                    Use this tool when pos-supplier validates a vendor's tax registrations at entry or offers
                    the scheme picker of the vendor form; do not use it to read tax rates, evidence rules or
                    information-return forms, which are getTaxRates, getTaxEvidenceRules and
                    getTaxInformationReturnForms instead.
                    Preconditions: this endpoint is internal-only (ADR-0021/ADR-0014), reached by direct
                    in-cluster calls from pos-supplier with the service authority, never through pos-api-gateway.
                    Required inputs: none; countryCode, two upper-case letters, narrows the answer to one country.
                    No events are emitted, no state changes and no tenant data is read; every value is
                    configuration held for expert advice, so source is always STUB.
                    Returns 200 with an empty list for a well-formed country without a configured scheme, and
                    400 VALIDATION_ERROR when countryCode is malformed.
                    """)
    @ApiResponse(responseCode = "200", description = "Schemes resolved (empty for a country without one)")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed countryCode",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:rates:view"})
    public ResponseEntity<TaxRegistrationSchemesResponse> getRegistrationSchemes(
            @RequestParam(required = false)
                    @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be two upper-case letters")
                    @Nullable
                    String countryCode) {
        return ResponseEntity.ok(registrationSchemes.read(countryCode));
    }
}
