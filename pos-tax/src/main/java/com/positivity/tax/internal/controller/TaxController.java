package com.positivity.tax.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.common.validation.IsoCountryCode;
import com.positivity.tax.internal.security.TaxPermissions;
import com.positivity.tax.internal.service.TaxProviderLifecycleService;
import com.positivity.tax.service.TaxCalculationService;
import com.positivity.tax.service.TaxRateLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for tax calculation endpoints.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/v1/tax")
@Tag(name = "Tax", description = "Tax calculation API")
public class TaxController {

    private final TaxCalculationService taxCalculationService;
    private final TaxProviderLifecycleService lifecycleService;
    private final TaxRateLookupService taxRateLookupService;

    public TaxController(
            TaxCalculationService taxCalculationService,
            TaxProviderLifecycleService lifecycleService,
            TaxRateLookupService taxRateLookupService) {
        this.taxCalculationService = taxCalculationService;
        this.lifecycleService = lifecycleService;
        this.taxRateLookupService = taxRateLookupService;
    }

    /**
     * Calculate tax for the provided line items and location.
     *
     * @param request the tax calculation request
     * @return the calculated tax response
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('" + TaxPermissions.CALCULATE + "')")
    @EmitEvent(id = "TAX_CALCULATE", apiVersion = "1")
    @Operation(operationId = "calculateTax", summary = "Calculate tax", description = """
                    Calculates tax for the supplied line items against the destination address and returns the
                    per-line and total tax amounts.
                    Use this tool whenever a quote, estimate or invoice needs tax figures; do not use it to make a
                    calculation permanent, which is commitTaxDocument.
                    Preconditions: none beyond an authenticated caller; when an exemption is claimed the referenced
                    certificate must already exist in the registry and be ACTIVE for the destination state on the
                    transaction date, otherwise tax is calculated as taxable.
                    Required inputs: lineItems (at least one) and destinationAddress with countryCode and postalCode;
                    currencyCode defaults to USD, calculationType defaults to SALE, and referenceId should carry the
                    source document id so the result can later be committed.
                    Emits a TAX_CALCULATE event and, in production mode, calls the configured external tax provider;
                    no provider document is created until commitTaxDocument is called.
                    Returns 400 when line items or the destination address are missing or malformed, and 500 when the
                    provider is unreachable in production mode.
                    """)
    @ApiResponse(responseCode = "200", description = "Tax calculated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid tax calculation request")
    @ApiResponse(responseCode = "500", description = "Tax calculation failed")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:calculate"})
    public ResponseEntity<TaxCalculationResponse> calculateTax(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "International tax calculation request",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "US destination example",
                                                            value =
                                                                    "{\"lineItems\":[{\"lineItemId\":\"1\",\"description\":\"Oil Change Service\",\"quantity\":1,\"unitPrice\":89.99,\"taxExempt\":false}],\"destinationAddress\":{\"countryCode\":\"US\",\"regionCode\":\"CA\",\"city\":\"Los Angeles\",\"postalCode\":\"90001\",\"line1\":\"123 Main St\"},\"currencyCode\":\"USD\",\"locale\":\"en-US\",\"referenceId\":\"550e8400-e29b-41d4-a716-446655440000\",\"referenceType\":\"ESTIMATE\"}")))
                    @Valid
                    @RequestBody
                    TaxCalculationRequest request) {
        log.info(
                "Received tax calculation request for {} line items, postal code(mask): {}",
                request.getLineItems().size(),
                maskForLog(request.getPostalCode()));

        TaxCalculationResponse response = taxCalculationService.calculateTax(request);

        log.info(
                "Tax calculation completed. Total tax: {}, Test mode: {}",
                response.getTotalTax(),
                response.isTestMode());

        return ResponseEntity.ok(response);
    }

    /**
     * Commit the provider tax document for a finalized invoice (story T6, decision D-T3).
     * <p>
     * Idempotent on {@code referenceId}. When the provider is unavailable this still
     * returns 200 with a {@code PENDING_COMMIT} status — the sale is never blocked and the
     * scheduled re-commit job trues it up (estimate-and-true-up).
     *
     * @param referenceId   the document code (source invoice id)
     * @param referenceType optional source transaction type label (defaults to {@code INVOICE})
     * @return the recorded lifecycle outcome
     */
    @PostMapping("/transactions/{referenceId}/commit")
    @PreAuthorize("hasAuthority('" + TaxPermissions.COMMIT + "')")
    @EmitEvent(id = "TAX_COMMIT", apiVersion = "1")
    @Operation(operationId = "commitTaxDocument", summary = "Commit tax document", description = """
                    Commits the provider tax document for a finalized invoice so the recorded tax becomes
                    filing-visible at the provider.
                    Use this tool when an invoice is finalized; do not use it to recalculate amounts, which is
                    calculateTax, and do not use it to reverse a commit, which is voidTaxDocument.
                    Preconditions: tax must already have been calculated for this referenceId with a committable
                    request, so that a provider document exists to commit.
                    Required inputs: referenceId (UUID) path parameter, which is the source invoice id; referenceType
                    is an optional query parameter defaulting to INVOICE.
                    Emits a TAX_COMMIT event and updates the stored provider transaction; the call is idempotent, so
                    an already-COMMITTED document is returned unchanged.
                    Returns 200 with status PENDING_COMMIT rather than an error when the provider call fails, because
                    a sale is never blocked on the provider, so callers must read the returned status instead of
                    treating 200 as a completed commit and leave the re-commit job to true it up.
                    """)
    @ApiResponse(responseCode = "200", description = "Commit recorded (COMMITTED or PENDING_COMMIT)")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:commit"})
    public ResponseEntity<TaxProviderTransactionResult> commit(
            @PathVariable UUID referenceId, @RequestParam(defaultValue = "INVOICE") String referenceType) {
        log.info("Received tax commit request for reference {}", referenceId);
        return ResponseEntity.ok(lifecycleService.commit(referenceId, referenceType));
    }

    /**
     * Void the provider tax document for an invoice reverted to DRAFT (story T6, R-T2).
     *
     * @param referenceId the document code (source invoice id)
     * @return the recorded lifecycle outcome
     */
    @PostMapping("/transactions/{referenceId}/void")
    @PreAuthorize("hasAuthority('" + TaxPermissions.COMMIT + "')")
    @EmitEvent(id = "TAX_VOID", apiVersion = "1")
    @Operation(operationId = "voidTaxDocument", summary = "Void tax document", description = """
                    Voids the provider tax document for an invoice that has been reverted to DRAFT, withdrawing the
                    committed tax from the provider.
                    Use this tool when a finalized invoice reverts to DRAFT; do not use it for ordinary corrections,
                    where calculateTax followed by commitTaxDocument replaces the figures instead.
                    Preconditions: a provider transaction must already exist for this referenceId, which means tax was
                    calculated and committed earlier.
                    Required inputs: referenceId (UUID) path parameter, which is the source invoice id; there is no
                    request body and no referenceType, because the existing transaction supplies it.
                    Emits a TAX_VOID event and moves the stored provider transaction to VOIDED, or to FAILED when the
                    provider rejects the void.
                    Returns 200 with status FAILED when the provider call fails, so callers must read the returned
                    status rather than treating 200 as a completed void.
                    """)
    @ApiResponse(responseCode = "200", description = "Void recorded")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:commit"})
    public ResponseEntity<TaxProviderTransactionResult> voidTransaction(@PathVariable UUID referenceId) {
        log.info("Received tax void request for reference {}", referenceId);
        return ResponseEntity.ok(lifecycleService.voidTransaction(referenceId));
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }

    /**
     * Check the current tax service mode.
     *
     * @return response indicating test mode status
     */
    @GetMapping("/mode")
    @PreAuthorize("hasAuthority('" + TaxPermissions.MODE_VIEW + "')")
    @Operation(operationId = "getTaxServiceMode", summary = "Get tax service mode", description = """
                    Returns whether the tax service is running against the external provider or against the built-in
                    test calculator.
                    Use this tool to interpret a calculation result before relying on it; do not use it as a health
                    check, which is the actuator health endpoint instead.
                    Preconditions: none; the mode is service configuration and is readable at any time.
                    Required inputs: none, and there is no request body or query parameter.
                    No events are emitted and no state changes; this is a read-only configuration projection.
                    Returns 200 in all cases, so an absent or unexpected mode value indicates a misconfigured
                    deployment rather than a request error.
                    """)
    @ApiResponse(responseCode = "200", description = "Tax service mode retrieved successfully")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:mode:view"})
    public ResponseEntity<ModeResponse> getMode() {
        boolean testMode = taxCalculationService.isTestMode();
        return ResponseEntity.ok(new ModeResponse(testMode ? "test" : "production", testMode));
    }

    /**
     * Look up the per-jurisdiction tax rates applicable to a destination address (issue #1522).
     *
     * @param countryCode ISO 3166-1 alpha-2 country code
     * @param postalCode  postal/ZIP code
     * @param regionCode  optional region/subdivision code
     * @param city        optional city
     * @param asOf        optional effective date; defaults to today
     * @return the resolved rate components and combined rate
     */
    @GetMapping("/rates")
    @PreAuthorize("hasAuthority('" + TaxPermissions.RATES_VIEW + "')")
    @Operation(operationId = "getTaxRates", summary = "Look up jurisdiction tax rates", description = """
                    Resolves the per-jurisdiction tax rates applicable to a destination address, without calculating
                    tax for any line items.
                    Use this tool to preview or display the rate breakdown for an address; do not use it to compute
                    tax on a cart or invoice, which is calculateTax.
                    Preconditions: this endpoint is internal-only (ADR-0021/ADR-0014) — it has no gateway route and
                    is reached only by direct in-cluster calls, never through pos-api-gateway.
                    Required inputs: countryCode (ISO 3166-1 alpha-2) and postalCode; regionCode and city narrow the
                    match further, and asOf (ISO-8601 date) defaults to today.
                    No events are emitted and no state changes; components are per-jurisdiction rates as decimal
                    fractions (not a blended estimate), and SPECIAL/DISTRICT jurisdiction types appear only when a
                    configured rule produces them — today's test-mode rules emit STATE/COUNTY/CITY.
                    Returns 400 when countryCode or postalCode are missing or malformed, and 501 when the configured
                    tax provider does not support rate-only lookup (every production provider today; AvaTax
                    rate-by-address is a documented follow-up, not yet implemented).
                    """)
    @ApiResponse(responseCode = "200", description = "Rates resolved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid address parameters")
    @ApiResponse(responseCode = "501", description = "Rate lookup not supported by the active tax provider")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:rates:view"})
    public ResponseEntity<TaxRateLookupResponse> getRates(
            @RequestParam @NotBlank @IsoCountryCode String countryCode,
            @RequestParam @NotBlank String postalCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        log.info("Received tax rate lookup request for postal code(mask): {}", maskForLog(postalCode));
        return ResponseEntity.ok(taxRateLookupService.lookupRates(countryCode, regionCode, city, postalCode, asOf));
    }

    /**
     * Response DTO for mode endpoint.
     */
    @Schema(description = "Current tax service operating mode")
    public record ModeResponse(
            @Schema(
                    description = "Human-readable tax service mode label",
                    example = "production",
                    requiredMode = REQUIRED)
            String mode,

            @Schema(
                    description = "Whether the tax service is running in test mode",
                    example = "false",
                    requiredMode = REQUIRED)
            boolean testMode) {}
}
