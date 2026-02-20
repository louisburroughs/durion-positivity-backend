package com.positivity.price.service;

import com.positivity.price.internal.dto.PriceQuoteRequest;
import com.positivity.price.internal.dto.PriceQuoteResponse;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for contextual product quote calculations.
 *
 * Issue: #51
 */
public interface PriceQuoteService {

    /**
     * Calculates quote pricing for the request context.
     *
     * @param request contextual quote request
     * @return calculated quote response
     */
    @NonNull
    PriceQuoteResponse calculatePrice(@NonNull PriceQuoteRequest request);
}
