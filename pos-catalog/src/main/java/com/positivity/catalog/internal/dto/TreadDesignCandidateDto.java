package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.enums.MatchTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * One product the matcher considered for a tread design, and how well it scored (#1645).
 *
 * <p>Shown to a reviewer so the decision they are asked to make is the machine's decision, visible —
 * "these three products resembled it, this much" — rather than an unexplained empty result.
 */
@Schema(description = "A product the matcher scored against a tread design, with its confidence tier.")
public record TreadDesignCandidateDto(
        @Schema(description = "The candidate catalog product.") @NonNull
        UUID productId,

        @Schema(description = "Trigram similarity in [0.0000, 1.0000] after the brand gate.", example = "0.8421")
        @NonNull
        BigDecimal score,

        @Schema(description = "What that score means under the configured thresholds.") @NonNull
        MatchTier tier) {}
