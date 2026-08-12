package com.positivity.supplier.service.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Bookkeeping summary of one vendor price-catalog (PRICAT) import run. Placeholder-level for
 * the CAP-317 foundation slice; grows with CAP-318.
 *
 * @param importManifestId identity of the import run
 * @param vendorProfileId vendor profile the catalog was fetched for
 * @param importedAt when the import run completed
 * @param entryCount number of catalog rows produced by the run; {@code >= 0}
 * @param sourceDocumentDate vendor catalog document date, when stated
 */
public record PriceCatalogImportSummary(
        @NonNull UUID importManifestId,
        @NonNull UUID vendorProfileId,
        @NonNull Instant importedAt,
        int entryCount,
        @Nullable LocalDate sourceDocumentDate) {

    public PriceCatalogImportSummary {
        Objects.requireNonNull(importManifestId, "importManifestId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(importedAt, "importedAt must not be null");
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must be >= 0");
        }
    }
}
