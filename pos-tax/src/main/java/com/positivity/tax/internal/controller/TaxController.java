package com.positivity.tax.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.internal.observability.BusinessSpanSupport;
import com.positivity.tax.service.TaxCalculationService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-tax");
    private static final String DOMAIN = "tax";
    private static final String TEAM = "tax-eng";

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
    @Operation(
            summary = "Calculate tax",
            description =
                    "Calculate tax for line items based on location. Routes to external service in production or test calculator in test mode.")
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
        Span span = TRACER.spanBuilder("Calculate Tax").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Calculate Tax");
        span.setAttribute("app.operation.type", "query");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            log.info(
                    "Received tax calculation request for {} line items, postal code(mask): {}",
                    request.getLineItems().size(),
                    maskForLog(request.getPostalCode()));

            TaxCalculationResponse response = taxCalculationService.calculateTax(request);

            log.info(
                    "Tax calculation completed. Total tax: {}, Test mode: {}",
                    response.getTotalTax(),
                    response.isTestMode());

            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
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
    @PreAuthorize("hasAuthority('tax:mode:view')")
    @Operation(
            summary = "Get tax service mode",
            description = "Check if the tax service is currently in test mode or production mode")
    @ApiResponse(responseCode = "200", description = "Tax service mode retrieved successfully")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:mode:view"})
    public ResponseEntity<ModeResponse> getMode() {
        boolean testMode = taxCalculationService.isTestMode();
        return ResponseEntity.ok(new ModeResponse(testMode ? "test" : "production", testMode));
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
