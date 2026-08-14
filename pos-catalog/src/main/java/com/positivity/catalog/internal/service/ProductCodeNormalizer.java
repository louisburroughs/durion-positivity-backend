package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ProductCodeKind;
import com.positivity.catalog.internal.entity.ProductCodeType;
import org.jspecify.annotations.NonNull;

/**
 * Shared normalisation for product codes so that write-time validation, lookup, and the duplicate
 * report all agree on what "the same code" means (ADR-0053 §5).
 *
 * <p>
 * Normalisation is deliberately minimal: surrounding whitespace is removed and a blank value
 * becomes null. Digits are not re-padded and check digits are not recomputed — a vendor code that
 * differs by a leading zero is a different code, and treating it otherwise would be the fuzzy
 * matching ADR-0053 forbids.
 */
public final class ProductCodeNormalizer {

    private ProductCodeNormalizer() {}

    /**
     * @param code raw code as supplied by a caller or a vendor document
     * @return the trimmed code, or null when the input is null or blank
     */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Maps the public code scheme onto the persisted enum. */
    @NonNull
    public static ProductCodeType toEntityType(@NonNull ProductCodeKind kind) {
        return switch (kind) {
            case UPC -> ProductCodeType.UPC;
            case EAN -> ProductCodeType.EAN;
        };
    }

    /** Maps the persisted enum onto the public code scheme. */
    @NonNull
    public static ProductCodeKind toKind(@NonNull ProductCodeType type) {
        return switch (type) {
            case UPC -> ProductCodeKind.UPC;
            case EAN -> ProductCodeKind.EAN;
        };
    }
}
