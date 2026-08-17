package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Manufacturer marketing enrichment for one tread design variant, published on
 * {@code supplier.events.v1} (CAP-324 #1230/#1257, ADR-0044 R1).
 *
 * <h2>Enrichment, not identity</h2>
 *
 * This describes how a manufacturer markets a tread design: names, marketing copy per language,
 * artwork, seasonality, vehicle type. It says nothing about what a product <em>is</em> — no
 * dimensions, no load index, no article code — because product identity and structure belong to
 * pos-catalog and a supplier fact must not be able to redefine them. A consumer that let this change
 * a product's identity would have handed a vendor edit rights over the catalogue.
 *
 * <h2>Matching is the consumer's problem, and may fail</h2>
 *
 * A tread design variant is a <em>design</em>, not an article: it has no EAN, because one design
 * spans many sizes each with their own. The identifiers here — brand, design names, the vendor's own
 * variant id — are what the vendor gives, published as stated. Whether they resolve to anything in
 * the catalogue is pos-catalog's decision, and an enrichment that matches nothing is an ordinary
 * outcome rather than an error.
 *
 * <h2>Identity, because the same variant is republished</h2>
 *
 * Both ingestion modes — change subscription and scheduled diffing — republish a variant whenever
 * the vendor's content changes, and a redelivery can repeat one. A consumer must key on
 * {@code (vendorProfileId, vendorVariantId)} rather than on the event.
 *
 * @param vendorProfileId  the vendor profile the enrichment was fetched from
 * @param supplierRef      that profile's alias at fetch time; descriptive, never a key
 * @param vendorVariantId  the vendor's own tread design variant identifier
 * @param brand            brand as stated
 * @param treadDesign      primary tread design name as stated
 * @param treadDesign2     secondary tread design name, where the vendor uses one
 * @param productName      the vendor's product name, where given
 * @param vehicleType      vehicle class the design is for, as stated
 * @param seasonality      summer, winter, all-season and so on, as the vendor states it. Not mapped
 *                         to a Durion vocabulary: the mapping is a catalogue decision
 * @param contentHash      hash of everything above plus the texts and images, so a consumer can skip
 *                         a republication whose content did not actually change
 * @param texts            marketing copy per language, as stated
 * @param images           artwork, as stored references — never vendor URLs
 * @param occurredAt       when the enrichment was fetched
 */
public record SupplierCatalogUpdatedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String vendorVariantId,
        @Nullable String brand,
        @Nullable String treadDesign,
        @Nullable String treadDesign2,
        @Nullable String productName,
        @Nullable String vehicleType,
        @Nullable String seasonality,
        @NonNull String contentHash,
        @NonNull List<SupplierCatalogEnrichmentText> texts,
        @NonNull List<SupplierCatalogEnrichmentImage> images,
        @NonNull Instant occurredAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.catalog.updated";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public SupplierCatalogUpdatedV1 {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(vendorVariantId, "vendorVariantId must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        Objects.requireNonNull(texts, "texts must not be null");
        Objects.requireNonNull(images, "images must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        texts = List.copyOf(texts);
        images = List.copyOf(images);
    }

    /** Whether any artwork on this enrichment is still missing and awaiting retry. */
    public boolean hasUnresolvedImages() {
        return images.stream().anyMatch(SupplierCatalogEnrichmentImage::unresolved);
    }
}
