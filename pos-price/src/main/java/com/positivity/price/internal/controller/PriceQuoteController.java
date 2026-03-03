package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.PriceQuoteRequest;
import com.positivity.price.internal.dto.PriceQuoteResponse;
import com.positivity.price.service.PriceQuoteService;
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
    public ResponseEntity<PriceQuoteResponse> calculatePriceQuote(@Valid @RequestBody PriceQuoteRequest request) {
        return ResponseEntity.ok(priceQuoteService.calculatePrice(request));
    }
}
