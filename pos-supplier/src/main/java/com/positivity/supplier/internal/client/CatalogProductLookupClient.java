package com.positivity.supplier.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic product resolution against pos-catalog (ADR-0053 §5).
 *
 * <p>The three outcomes are kept distinct on purpose. "No product carries this code" is catalog
 * work; "the catalog could not be reached" is a retry; "more than one product carries it" is dirty
 * catalog data. Collapsing any two of them would either strand lines that a retry would have
 * matched, or bury a data defect as if it were an ordinary miss.
 */
public interface CatalogProductLookupClient {

    /**
     * Resolves a product by an exact code under one scheme.
     *
     * @param codeType {@code EAN} or {@code UPC}
     * @param code     exact code value
     * @return the outcome; never null
     */
    @NonNull
    LookupResult findProductByCode(@NonNull String codeType, @NonNull String code);

    /** Outcome of one lookup. */
    sealed interface LookupResult {

        /** Exactly one product carries the code. */
        record Matched(@NonNull UUID productId) implements LookupResult {}

        /** No product carries the code; a catalog fix is required before this line can apply. */
        record NotFound() implements LookupResult {}

        /** More than one product carries the code — matching is refused rather than guessed. */
        record Ambiguous(@Nullable String detail) implements LookupResult {}

        /** The catalog could not be consulted; the line is re-applicable without a vendor re-fetch. */
        record Unavailable(@Nullable String detail) implements LookupResult {}
    }
}
