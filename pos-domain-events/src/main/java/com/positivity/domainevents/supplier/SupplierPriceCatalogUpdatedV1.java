package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.pricecatalog.updated} v1 on {@code supplier.events.v1}
 * (ADR-0053 §7, ADR-0049 §3).
 *
 * <p>Published by pos-supplier as one <strong>chunk</strong> of a PRICAT import: a dealer catalog
 * runs to tens of thousands of lines, which does not fit one broker message, so the import is
 * split and the envelope's {@code aggregateId} is the import manifest with {@code aggregateVersion}
 * as the chunk sequence. A consumer applies chunks incrementally and idempotently and knows the
 * set is complete only when {@link SupplierPriceCatalogImportCompletedV1} arrives with a matching
 * {@code chunkCount}.
 *
 * <p>Vendor prices are purchasing input. Neither the pos-price quote chain nor the pos-catalog
 * price-book resolver may read what this event carries (ADR-0053 §4) — the suggested retail price
 * is display reference data labelled with its PRICAT source, never an applied sell price.
 *
 * <p>Scope is the buyer account and market, never a location (ADR-0053 §3). A deployment that
 * needs different supplier costs per store group models that as separate vendor profiles, each
 * with its own PRICAT binding.
 *
 * @param importManifestId   the import this chunk belongs to; also the envelope aggregate id
 * @param vendorProfileId    vendor profile that produced the import (ADR-0050 §1 identity)
 * @param supplierRef        profile alias at fetch time; descriptive only, never a key
 * @param chunkSequence      1-based sequence of this chunk within the import
 * @param chunkCount         total number of chunks in the import, so a consumer can detect gaps
 * @param sourceDocumentId   vendor document id, when the vendor stated one
 * @param sourceDocumentDate vendor document date, when the vendor stated one
 * @param buyerAccountNumber buyer account the catalog was requested for (market scope)
 * @param countryCode        ISO 3166-1 alpha-2 market of the catalog
 * @param currency           ISO 4217 currency of every price in this chunk
 * @param protocolVersion    vendor norm version that produced the lines, e.g. {@code B4_0}
 * @param fetchedAt          when the producer fetched the document
 * @param lines              matched lines in this chunk; never empty for a published chunk
 */
public record SupplierPriceCatalogUpdatedV1(
        @NonNull UUID importManifestId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        int chunkSequence,
        int chunkCount,
        @Nullable String sourceDocumentId,
        @Nullable LocalDate sourceDocumentDate,
        @NonNull String buyerAccountNumber,
        @NonNull String countryCode,
        @NonNull String currency,
        @NonNull String protocolVersion,
        @NonNull Instant fetchedAt,
        @NonNull List<SupplierPriceCatalogLine> lines) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.pricecatalog.updated";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;
}
