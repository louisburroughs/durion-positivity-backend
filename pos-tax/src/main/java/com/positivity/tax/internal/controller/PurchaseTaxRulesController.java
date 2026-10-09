package com.positivity.tax.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.dto.PurchaseTaxRulesResponse;
import com.positivity.tax.internal.security.TaxPermissions;
import com.positivity.tax.internal.service.PurchaseTaxRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The purchase-tax rules stub (CAP:550 S43, AW44, AW48): per country, whether a vendor bill charging tax on goods for
 * resale is held and whether an untaxed expense bill self-assesses (use) tax, answered with {@code source = STUB}. It
 * reads no tenant data, changes no state and emits no event. Internal-only: pos-accounting calls it with the service
 * authority, and there is no gateway route (ADR-0021).
 */
@Validated
@RestController
@RequestMapping("/v1/tax")
@Tag(name = "Tax", description = "Tax calculation API")
public class PurchaseTaxRulesController {

    private final PurchaseTaxRules purchaseTaxRules;

    public PurchaseTaxRulesController(PurchaseTaxRules purchaseTaxRules) {
        this.purchaseTaxRules = purchaseTaxRules;
    }

    /**
     * The purchase-tax rules a country configures, on a date.
     *
     * @param countryCode two upper-case letters
     * @param asOf        optional date; defaults to today
     * @return the rules
     */
    @GetMapping("/purchase-rules")
    @PreAuthorize("hasAuthority('" + TaxPermissions.RATES_VIEW + "')")
    @Operation(operationId = "getTaxPurchaseRules", summary = "Read a country's purchase-tax rules", description = """
                    Returns a country's purchase-tax rules on a date: whether a vendor bill charging tax on goods for
                    resale is held for a person (HOLD or ALLOW), and whether a bill stating no tax self-assesses use
                    tax on its expense lines.
                    Use this tool when pos-accounting decides or shows a vendor bill's purchase tax; do not use it to
                    price the self-assessed tax, which is calculateTax with calculationType USE instead.
                    Preconditions: this endpoint is internal-only (ADR-0021/ADR-0014), reached by direct in-cluster
                    calls from pos-accounting with the service authority, never through pos-api-gateway.
                    Required inputs: countryCode, two upper-case letters; asOf (ISO-8601 date) defaults to today.
                    No events are emitted, no state changes and no tenant data is read; every rule is configuration
                    held for expert advice, so source is always STUB, and a country without rules answers configured
                    false with ALLOW and false.
                    Returns 200 for every well-formed country, and 400 VALIDATION_ERROR when countryCode or asOf is
                    missing or malformed.
                    """)
    @ApiResponse(responseCode = "200", description = "Rules resolved (configured false for a country without any)")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or malformed countryCode or asOf",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:rates:view"})
    public ResponseEntity<PurchaseTaxRulesResponse> getPurchaseRules(
            @RequestParam
                    @NotBlank
                    @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be two upper-case letters")
                    String countryCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(purchaseTaxRules.read(countryCode, asOf));
    }
}
