package com.positivity.tax.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.dto.InformationReturnFormsResponse;
import com.positivity.tax.internal.security.TaxPermissions;
import com.positivity.tax.internal.service.InformationReturnForms;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The information-return forms stub (CAP:550 #2615, AW48): per country, the configured forms, their boxes
 * and the payee-id schemes, answered with {@code source = STUB}. It reads no tenant data, changes no state
 * and emits no event. Internal-only: pos-accounting relays it through its own front door (ADR-0071), and
 * there is no gateway route (ADR-0021).
 */
@Validated
@RestController
@RequestMapping("/v1/tax")
@Tag(name = "Tax", description = "Tax calculation API")
public class InformationReturnFormsController {

    private final InformationReturnForms informationReturnForms;

    public InformationReturnFormsController(InformationReturnForms informationReturnForms) {
        this.informationReturnForms = informationReturnForms;
    }

    /**
     * The information-return forms a country configures.
     *
     * @param countryCode two upper-case letters
     * @return the forms
     */
    @GetMapping("/information-return-forms")
    @PreAuthorize("hasAuthority('" + TaxPermissions.RATES_VIEW + "')")
    @Operation(
            operationId = "getTaxInformationReturnForms",
            summary = "List a country's information-return forms",
            description = """
                    Returns the information-return forms a country configures, each with its boxes and the payee-id
                    schemes a payee may be reported under, so a vendor can be marked reportable in a form and box.
                    Use this tool when pos-accounting validates or offers a vendor's information-return flag; do not
                    use it to read tax rates or evidence rules, which are getTaxRates and getTaxEvidenceRules instead.
                    Preconditions: this endpoint is internal-only (ADR-0021/ADR-0014), reached by direct in-cluster
                    calls from pos-accounting with the service authority, never through pos-api-gateway.
                    Required inputs: countryCode, two upper-case letters.
                    No events are emitted, no state changes and no tenant data is read; every value is configuration
                    held for expert advice, so source is always STUB.
                    Returns 200 with an empty list for a country without a configured form, and 400 VALIDATION_ERROR
                    when countryCode is missing or malformed.
                    """)
    @ApiResponse(responseCode = "200", description = "Forms resolved (empty for a country without one)")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or malformed countryCode",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:rates:view"})
    public ResponseEntity<InformationReturnFormsResponse> getInformationReturnForms(
            @RequestParam
                    @NotBlank
                    @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be two upper-case letters")
                    String countryCode) {
        return ResponseEntity.ok(informationReturnForms.read(countryCode));
    }
}
