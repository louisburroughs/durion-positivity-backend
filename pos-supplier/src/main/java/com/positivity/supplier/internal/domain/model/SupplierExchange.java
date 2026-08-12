package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * A canonical exchange result: the payload a port operation produced plus the
 * {@link ExchangeMetadata} describing how it was obtained (architecture doc §6.1 — ports
 * "return canonical responses plus an ExchangeMetadata").
 *
 * @param <T> the canonical payload type
 * @param payload the canonical result
 * @param metadata the exchange metadata (audit reference, correlation, norm, unmapped fields)
 */
public record SupplierExchange<T>(
        @NonNull T payload, @NonNull ExchangeMetadata metadata) {

    public SupplierExchange {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
