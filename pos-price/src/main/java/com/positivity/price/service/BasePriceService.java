package com.positivity.price.service;

import com.positivity.price.service.model.BasePriceView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for history-retaining product base price records (ADR-0054 §4).
 *
 * Windows are half-open on {@code Instant}: a row is effective for instants {@code t} where
 * {@code effectiveFrom <= t < effectiveTo}; a null {@code effectiveTo} means open-ended.
 */
public interface BasePriceService {

    /**
     * Appends a base-price change for a product. If an open window exists for the same
     * (productId, currency) it is closed at {@code effectiveFrom} and a new open window is
     * appended; a call matching the current open window's MSRP is an idempotent no-op.
     * A {@code effectiveFrom} that does not start strictly after the latest existing window's
     * start is rejected.
     *
     * @param productId     product identifier
     * @param msrp          base price amount
     * @param currency      currency code
     * @param effectiveFrom effective-from instant of the new window
     * @return identifier of the effective base-price row (the appended row, or the unchanged
     *         open row on a no-op)
     */
    @NonNull
    UUID saveBasePrice(
            @NonNull UUID productId,
            @NonNull BigDecimal msrp,
            @NonNull String currency,
            @NonNull Instant effectiveFrom);

    /**
     * Returns all retained base-price windows for a product, newest effective-from first.
     *
     * @param productId product identifier
     * @return retained windows, newest first; empty if none
     */
    @NonNull
    List<BasePriceView> getBasePriceHistory(@NonNull UUID productId);

    /**
     * Returns the base-price window covering the given instant, if any.
     *
     * @param productId product identifier
     * @param at        pricing instant
     * @return the covering window, or empty when no window covers {@code at}
     */
    Optional<BasePriceView> getBasePriceAt(@NonNull UUID productId, @NonNull Instant at);
}
