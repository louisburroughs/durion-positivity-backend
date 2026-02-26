package com.positivity.catalog.internal.client;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Client for the pos-price service price-quote endpoint.
 * Calls POST /v1/price/quotes for contextual pricing.
 *
 * Issue: CAP-247 Story #16
 */
public interface PricingClient {

    /**
     * Fetches a price quote for a single unit of a product at a given location
     * using the specified customer tier.
     *
     * @param productId      product identifier
     * @param locationId     location/store identifier
     * @param customerTierId customer tier for pricing context
     * @return Optional containing quote response, or empty if service is
     *         unavailable
     */
    Optional<PriceQuoteClientResponse> fetchPrice(UUID productId, UUID locationId, UUID customerTierId);

    /** Local response record mirroring the relevant pos-price response fields. */
    record PriceQuoteClientResponse(
            BigDecimal msrpAmount,
            String msrpCurrency,
            BigDecimal unitPriceAmount,
            String priceSource) {
    }
}
