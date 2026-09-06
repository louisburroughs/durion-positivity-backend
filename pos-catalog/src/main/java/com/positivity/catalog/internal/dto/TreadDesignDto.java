package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Manufacturer marketing enrichment for a tread design (CAP-324 #1352).
 *
 * <p>Every field here is vendor-supplied, published as MKCAT stated it — distinguishable from
 * catalog-owned product data by virtue of living on this DTO at all rather than on the product's own
 * fields. A shop can tell what the manufacturer said from what the business entered because the two
 * are never merged into one record.
 */
@Schema(description = "Vendor-supplied tread-design marketing enrichment, distinct from catalog-owned product data.")
public record TreadDesignDto(
        @Schema(description = "This design's id.") @NonNull UUID id,

        @Schema(description = "Vendor profile the enrichment was fetched from.") @NonNull
        UUID vendorProfileId,

        @Schema(description = "That profile's alias, descriptive only.") @NonNull
        String supplierRef,

        @Schema(description = "The vendor's own tread design variant identifier.") @NonNull
        String vendorVariantId,

        @Schema(description = "Brand as stated by the vendor.") @Nullable
        String brand,

        @Schema(description = "Primary tread design name as stated.") @Nullable
        String treadDesign,

        @Schema(description = "Secondary tread design name, where the vendor uses one.") @Nullable
        String treadDesign2,

        @Schema(description = "Vendor's product name, where given.") @Nullable
        String productName,

        @Schema(description = "Vehicle class as stated.") @Nullable
        String vehicleType,

        @Schema(description = "Seasonality as the vendor states it, not mapped to a Durion vocabulary.") @Nullable
        String seasonality,

        @Schema(description = "Whether any artwork on this design is still missing and awaiting retry.")
        boolean hasUnresolvedImages,

        @Schema(description = "Marketing copy per language.") @NonNull
        List<TreadDesignTextDto> texts,

        @Schema(description = "Artwork references.") @NonNull
        List<TreadDesignImageDto> images,

        @Schema(description = "Where this design stands in the enrichment review cycle.", example = "REVIEW") @NonNull
        TreadDesignMatchState matchState,

        @Schema(description = "When the review state last changed.") @NonNull
        Instant matchStateAt,

        @Schema(description = "Reviewer who last resolved this design, when one has.") @Nullable
        String resolvedBy,

        @Schema(description = "Note the reviewer left with that resolution.") @Nullable
        String resolutionNote,

        @Schema(description = "When a deferred design should return to the worklist, when set.") @Nullable
        Instant deferUntil,

        @Schema(
                description = "Products the matcher scored against this design, best first."
                        + " Empty on the product-scoped read, which is about one resolved match rather"
                        + " than about a pending decision.")
        @NonNull
        List<TreadDesignCandidateDto> candidates,

        @Schema(description = "When this enrichment was last applied.") @NonNull
        Instant updatedAt) {}
