package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.PriceQuoteRequest;
import com.positivity.price.internal.dto.PriceQuoteResponse;
import com.positivity.price.service.PriceQuoteService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Operation(operationId = "calculatePriceQuote", summary = "Calculate Contextual Price Quote", description = """
                    Calculates a contextual unit and extended price for a product by resolving, in order, the base \
                    MSRP effective at the pricing instant, then an active location price override that replaces the \
                    running price, then a customer tier discount rate applied to the running price.
                    Use this tool to price a line for an estimate or cart; do not use applyPromotionOffer here, \
                    because promotions are layered onto the estimate separately, and use getPricingSnapshotById to \
                    re-read a previously captured price instead of recalculating.
                    Preconditions: a base price for the product and quote currency must be effective at the pricing \
                    instant; location overrides and tier rules are optional layers that apply only when active.
                    Required inputs: productId, locationId, and customerTierId (UUIDs) plus quantity (minimum 1); \
                    effectiveTimestamp defaults to the current time, and currency defaults to the configured \
                    pos.price.default-currency (USD unless overridden).
                    Emits a PRICE_QUOTE_CALCULATE event but writes no pricing state; the unit price is rounded \
                    half-even to two decimals, priceSource is MSRP_FALLBACK when only the base price applied and \
                    CALCULATED otherwise, and each resolution step is itemised in pricingBreakdown.
                    Returns 404 with code PRICE_BASE_UNAVAILABLE when no base price is effective for the product, \
                    currency, and instant.
                    """)
    @ApiResponse(responseCode = "200", description = "Price quote calculated successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid quote request.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No base price effective for the product at the pricing instant (PRICE_BASE_UNAVAILABLE).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PriceQuoteResponse> calculatePriceQuote(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Pricing context identifying the product, quantity, location, customer tier, and optional instant and currency.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Tiered quote", value = """
                                                                    {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01",
                                                                     "quantity":2,
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02",
                                                                     "customerTierId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03",
                                                                     "effectiveTimestamp":"2026-03-05T21:15:00Z",
                                                                     "currency":"USD"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PriceQuoteRequest request) {
        return ResponseEntity.ok(priceQuoteService.calculatePrice(request));
    }
}
