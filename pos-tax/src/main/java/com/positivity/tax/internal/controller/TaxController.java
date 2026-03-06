package com.positivity.tax.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.service.TaxCalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for tax calculation endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/v1/tax")
@Tag(name = "Tax", description = "Tax calculation API")
public class TaxController {

    private final TaxCalculationService taxCalculationService;

    public TaxController(TaxCalculationService taxCalculationService) {
        this.taxCalculationService = taxCalculationService;
    }

    /**
     * Calculate tax for the provided line items and location.
     *
     * @param request the tax calculation request
     * @return the calculated tax response
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('tax:calculate')")
    @EmitEvent(id = "TAX_CALCULATE", apiVersion = "1")
    @Operation(summary = "Calculate tax", description = "Calculate tax for line items based on location. Routes to external service in production or test calculator in test mode.")
    @ApiResponse(responseCode = "200", description = "Tax calculated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid tax calculation request")
    @ApiResponse(responseCode = "500", description = "Tax calculation failed")
    public ResponseEntity<TaxCalculationResponse> calculateTax(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "International tax calculation request", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "US destination example", value = "{\"lineItems\":[{\"lineItemId\":\"1\",\"description\":\"Oil Change Service\",\"quantity\":1,\"unitPrice\":89.99,\"taxExempt\":false}],\"destinationAddress\":{\"countryCode\":\"US\",\"regionCode\":\"CA\",\"city\":\"Los Angeles\",\"postalCode\":\"90001\",\"line1\":\"123 Main St\"},\"currencyCode\":\"USD\",\"locale\":\"en-US\",\"referenceId\":\"550e8400-e29b-41d4-a716-446655440000\",\"referenceType\":\"ESTIMATE\"}"))) @Valid @RequestBody TaxCalculationRequest request) {
        log.info("Received tax calculation request for {} line items, postal code: {}",
                request.getLineItems().size(), request.getPostalCode());

        TaxCalculationResponse response = taxCalculationService.calculateTax(request);

        log.info("Tax calculation completed. Total tax: {}, Test mode: {}",
                response.getTotalTax(), response.isTestMode());

        return ResponseEntity.ok(response);
    }

    /**
     * Check the current tax service mode.
     *
     * @return response indicating test mode status
     */
    @GetMapping("/mode")
    @PreAuthorize("hasAuthority('tax:mode:view')")
    @Operation(summary = "Get tax service mode", description = "Check if the tax service is currently in test mode or production mode")
    @ApiResponse(responseCode = "200", description = "Tax service mode retrieved successfully")
    public ResponseEntity<ModeResponse> getMode() {
        boolean testMode = taxCalculationService.isTestMode();
        return ResponseEntity.ok(new ModeResponse(
                testMode ? "test" : "production",
                testMode));
    }

    /**
     * Response DTO for mode endpoint.
     */
    public record ModeResponse(String mode, boolean testMode) {
    }
}
