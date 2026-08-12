package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.spi.ExchangeOutcome;
import org.jspecify.annotations.NonNull;

/**
 * Implemented by internal control-flow exceptions that already carry an attempt's classification, so
 * the circuit breaker can decide whether a failure reflects transport health without re-deriving it.
 *
 * <p>Exists so {@link SupplierBreakerRegistry#countsAsTransportFailure} can be a predicate over the
 * classification the base client already computed, rather than a second, drifting copy of the
 * classification rules.
 */
public interface ExchangeOutcomeCarrier {

    /** The classification of the attempt this exception represents. */
    @NonNull
    ExchangeOutcome exchangeOutcome();
}
