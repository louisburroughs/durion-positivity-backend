package com.positivity.price.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Service contract for product base MSRP records. */
public interface BasePriceService {

    /**
     * Creates or updates a product base price record from scalar values.
     *
     * @param productId     product identifier
     * @param msrp          MSRP amount
     * @param currency      currency code
     * @param effectiveFrom effective-from instant
     * @return persisted product identifier
     */
    @NonNull
    UUID saveBasePrice(
            @NonNull UUID productId,
            @NonNull BigDecimal msrp,
            @NonNull String currency,
            @NonNull Instant effectiveFrom);
}
