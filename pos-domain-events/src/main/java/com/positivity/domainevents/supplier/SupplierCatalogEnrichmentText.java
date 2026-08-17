package com.positivity.domainevents.supplier;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Marketing copy in one language (CAP-324 #1230).
 *
 * <p>Kept per language rather than flattened to a single "description". A tyre's marketing text is
 * written per market, and collapsing it would force whoever consumes this to pick a language at
 * ingestion time — before anyone knows which shop will read it.
 *
 * @param languageCode the vendor's language code, as stated. Not normalised to a locale: a value
 *                     this side did not recognise is more useful than one it invented
 * @param name         product name in that language, where given
 * @param description  marketing description in that language, where given
 * @param footNotes    legal or qualifying text the vendor attached, where given
 */
public record SupplierCatalogEnrichmentText(
        @NonNull String languageCode,
        @Nullable String name,
        @Nullable String description,
        @Nullable String footNotes) {

    public SupplierCatalogEnrichmentText {
        Objects.requireNonNull(languageCode, "languageCode must not be null");
    }
}
