package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Product-code schemes that carry a per-type uniqueness guarantee and therefore support
 * deterministic exact-match lookup (ADR-0053 §5).
 *
 * <p>
 * These are the only code types the catalog constrains: at most one product may carry a given
 * value for a given kind. Any other identifier (SKU, supplier code, manufacturer part number) is
 * matched through its own contract and is not part of this enum.
 */
@Schema(description = "Product-code scheme that supports deterministic exact-match lookup.")
public enum ProductCodeKind {
    /** Universal Product Code, matched against {@code xReferenceCode} values on PRICAT lines. */
    UPC,
    /** International Article Number, the primary product identity carried by PRICAT lines. */
    EAN
}
