package com.positivity.inventory.internal.dto.supplierhint;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintAvailability;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One vendor's last statement about the availability of one article (CAP-322, #1312).
 *
 * <p>This is an availability <strong>hint</strong>, not a commercial commitment and not stock we
 * own. It never contributes to on-hand availability-to-promise and never to valuation. A caller
 * showing it to a user should show it as the vendor's word, with its age.
 *
 * <p>Read it through {@link #availability()} rather than through {@link #quantity()} alone: a null
 * quantity means one thing under {@code QUANTITY_NOT_STATED} (the vendor listed the article and
 * said nothing about how many) and another under {@code STALE_UNKNOWN} (the vendor's figure is too
 * old to repeat). Neither is zero. Zero appears only as {@code REPORTED} with a quantity of 0,
 * which is the vendor explicitly saying it has none. An article no vendor mentioned yields no
 * entry at all — absence here is absence of information, never an out-of-stock.
 */
@Schema(
        description = "A vendor's reported availability for one article: an advisory hint carrying its own"
                + " age, never owned stock and never an ATP figure.")
public record SupplierStockHintView(
        @Schema(
                description = "Vendor profile that reported this. Hints are never aggregated across"
                        + " vendors — each vendor's statement stands on its own.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                requiredMode = REQUIRED)
        UUID vendorProfileId,

        @Schema(
                description = "Vendor alias at fetch time; descriptive only.",
                example = "ediwheel-b21",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String supplierRef,

        @Schema(description = "Which identifier this hint is keyed on.", requiredMode = REQUIRED)
        SupplierHintIdentityKind identityKind,

        @Schema(description = "Value of the keyed identifier, as the vendor stated it.", requiredMode = REQUIRED)
        String identityValue,

        @Schema(description = "EAN/GTIN as the vendor stated it.", requiredMode = NOT_REQUIRED) @Nullable
        String articleEan,

        @Schema(description = "The vendor's own article code.", requiredMode = NOT_REQUIRED) @Nullable
        String supplierArticleCode,

        @Schema(description = "Our article code as the vendor holds it.", requiredMode = NOT_REQUIRED) @Nullable
        String buyersArticleId,

        @Schema(description = "Vendor article description.", requiredMode = NOT_REQUIRED) @Nullable
        String description,

        @Schema(
                description = "Catalog product this article resolved to; null while the article is"
                        + " unresolved, which is a normal state and not an error.",
                requiredMode = NOT_REQUIRED)
        @Nullable
        UUID resolvedProductId,

        @Schema(
                description = "Quantity the vendor reported. Null when the vendor stated none or when"
                        + " the hint is too stale to repeat — read `availability` to tell those apart."
                        + " A value of 0 is the vendor explicitly reporting no stock.",
                example = "12",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Integer quantity,

        @Schema(description = "How to read this hint.", requiredMode = REQUIRED)
        SupplierHintAvailability availability,

        @Schema(
                description = "The vendor's own statement of when its snapshot was true. Null when the"
                        + " vendor stated no time; judge freshness on this whenever it is present.",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Instant snapshotAsOf,

        @Schema(
                description = "Whether the age shown is the vendor's figure (VENDOR) or only our fetch"
                        + " clock (FETCH), which bounds the age from below without establishing it.",
                requiredMode = REQUIRED)
        SupplierHintAsOfSource asOfSource,

        @Schema(description = "When we asked the vendor.", requiredMode = REQUIRED)
        Instant fetchedAt,

        @Schema(
                description = "False when the snapshot this hint came from never arrived in full. The"
                        + " hint itself is still what the vendor said; the snapshot around it is"
                        + " incomplete.",
                requiredMode = REQUIRED)
        boolean snapshotComplete,

        @Schema(description = "Chunks of the source snapshot applied so far.", requiredMode = REQUIRED)
        int chunksApplied,

        @Schema(description = "Chunks the source snapshot was split into.", requiredMode = REQUIRED)
        int chunkCount) {}
