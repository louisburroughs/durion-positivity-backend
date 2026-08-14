package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * One product-code value carried by more than one product — data that the per-type uniqueness
 * constraint forbids and that must be cleaned up before the constraint can be applied.
 *
 * <p>
 * The same report is produced by the pre-constraint Flyway migration into
 * {@code product_code_duplicate_report}; this view exists so operators and tests can read it
 * through the module's own contract instead of querying the schema.
 *
 * @param codeType   the code scheme carrying the duplicate value
 * @param code       the duplicated code value
 * @param productIds identifiers of every product carrying it, ordered by identifier
 */
@Schema(description = "A product-code value carried by more than one product, pending cleanup.")
public record ProductCodeDuplicate(
        @Schema(description = "Code scheme carrying the duplicate value.", implementation = ProductCodeKind.class)
        ProductCodeKind codeType,

        @Schema(description = "The duplicated code value.", example = "0123456789012")
        String code,

        @Schema(description = "Identifiers of every product carrying the duplicated value.")
        List<UUID> productIds) {

    /** Number of products carrying the duplicated value; always at least two. */
    @Schema(description = "Number of products carrying the duplicated value.", example = "2")
    public int productCount() {
        return productIds.size();
    }
}
