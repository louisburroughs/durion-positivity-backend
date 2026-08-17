package com.positivity.supplier.internal.domain.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A manufacturer's marketing view of one tread design variant (CAP-324 #1230).
 *
 * <p>Everything here is as the catalogue states it. Seasonality is not mapped to a Durion
 * vocabulary, language codes are not normalised to locales, and vehicle type is not translated —
 * each of those is a catalogue decision, and making it here would bake one reading of a vendor's
 * words into the integration layer where nobody would find it again.
 *
 * @param vendorVariantId the catalogue's own variant identifier
 * @param brand           as stated
 * @param treadDesign     primary design name as stated
 * @param treadDesign2    secondary design name, where used
 * @param productName     the vendor's product name, where given
 * @param vehicleType     as stated
 * @param seasonality     as stated
 * @param texts           marketing copy per language
 * @param imageUris       where the catalogue publishes artwork, paired with the vendor's own type
 *                        label. These are fetch targets, not render sources
 */
public record MarketingVariant(
        @NonNull String vendorVariantId,
        @Nullable String brand,
        @Nullable String treadDesign,
        @Nullable String treadDesign2,
        @Nullable String productName,
        @Nullable String vehicleType,
        @Nullable String seasonality,
        @NonNull List<MarketingText> texts,
        @NonNull List<MarketingImageRef> imageUris) {

    public MarketingVariant {
        Objects.requireNonNull(vendorVariantId, "vendorVariantId must not be null");
        Objects.requireNonNull(texts, "texts must not be null");
        Objects.requireNonNull(imageUris, "imageUris must not be null");
        texts = List.copyOf(texts);
        imageUris = List.copyOf(imageUris);
    }

    /** Marketing copy in one language, as published. */
    public record MarketingText(
            @NonNull String languageCode,
            @Nullable String name,
            @Nullable String description,
            @Nullable String footNotes) {
        public MarketingText {
            Objects.requireNonNull(languageCode, "languageCode must not be null");
        }
    }

    /** Where a piece of artwork is published, and what the vendor calls it. */
    public record MarketingImageRef(
            @Nullable String imageType, @NonNull String uri) {
        public MarketingImageRef {
            Objects.requireNonNull(uri, "uri must not be null");
        }
    }
}
