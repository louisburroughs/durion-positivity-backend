package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.PriceQuoteRequest;
import com.positivity.price.internal.dto.PriceQuoteResponse;
import com.positivity.price.internal.observability.BusinessSpanSupport;
import com.positivity.price.service.PriceQuoteService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for contextual price quote operations.
 *
 * Issue: #51
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/price/quotes")
@Tag(name = "Price Quotes", description = "Contextual price quote operations")
public class PriceQuoteController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PriceQuoteController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-price");
    private static final String DOMAIN = "pricing";
    private static final String TEAM = "price-eng";

    private final PriceQuoteService priceQuoteService;

    public PriceQuoteController(PriceQuoteService priceQuoteService) {
        this.priceQuoteService = priceQuoteService;
    }

    /**
     * Calculates contextual quote pricing for product/location/tier.
     *
     * @param request quote request payload
     * @return contextual quote response
     */
    @PostMapping
    @EmitEvent(id = "PRICE_QUOTE_CALCULATE", apiVersion = "1")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Calculate contextual price quote",
            description =
                    "Calculates a contextual price quote using request inputs such as product, location, customer tier, and pricing context.")
    @ApiResponse(responseCode = "200", description = "Price quote calculated successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid quote request.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PriceQuoteResponse> calculatePriceQuote(@Valid @RequestBody PriceQuoteRequest request) {
        Span span = TRACER.spanBuilder("Calculate Price Quote").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Calculate Price Quote");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            PriceQuoteResponse response = priceQuoteService.calculatePrice(request);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Calculated price quote");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }
}
