package com.positivity.supplier.internal.mktcat.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishCompletedV1;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Builds the MKCAT events, for the import and for a re-publication alike.
 *
 * <h2>Why one factory</h2>
 *
 * {@link MktCatVariantStager} publishes a variant when its content changes and
 * {@link MktCatRepublisher} publishes it again on request. Both must describe a staged variant
 * identically — the consumer decides whether anything changed by comparing {@code contentHash}, so
 * a field that one path read off the vendor document and the other off the staged row would make a
 * recovery look like a vendor edit. Every field is therefore read off the staged row here, the same
 * way {@code PriceCatalogEventFactory} reads PRICAT events off the manifest row.
 */
final class MktCatEventFactory {

    private static final String SOURCE = "pos-supplier";

    private MktCatEventFactory() {}

    /**
     * One staged variant as a {@code supplier.catalog.updated} event.
     *
     * <p>Every call mints a <strong>new</strong> {@code eventId}: a re-publication is a new delivery
     * of an old fact, and pretending otherwise would make the consumer's event-id guard swallow the
     * recovery.
     *
     * @param row        the staged variant, already saved, so its id is the partition key
     * @param enrichedAt when the enrichment was fetched — on a re-publication the instant the
     *                   variant was last published, not the instant of the re-emit
     * @param emittedAt  when this event is queued
     */
    @NonNull
    static DomainEventEnvelope<SupplierCatalogUpdatedV1> variantUpdated(
            @NonNull SupplierMktCatVariantEntity row,
            @NonNull List<SupplierCatalogEnrichmentText> texts,
            @NonNull List<SupplierCatalogEnrichmentImage> images,
            @NonNull Instant enrichedAt,
            @NonNull Instant emittedAt) {
        return new DomainEventEnvelope<>(
                UUIDv7Generator.generate(),
                SupplierCatalogUpdatedV1.EVENT_TYPE,
                SupplierCatalogUpdatedV1.SCHEMA_VERSION,
                // Keyed on the staged row, so every enrichment for one variant lands on one
                // partition in order: a stale republication overtaking a newer one would put
                // withdrawn marketing copy back on a product.
                row.getSupplierMktCatVariantId(),
                0L,
                emittedAt,
                SOURCE,
                // tenantId: stamped by the outbox writer from the bound tenant (ADR-0062 §3)
                null,
                null,
                SOURCE,
                new SupplierCatalogUpdatedV1(
                        row.getVendorProfileId(),
                        row.getSupplierRef(),
                        row.getVendorVariantId(),
                        row.getBrand(),
                        row.getTreadDesign(),
                        row.getTreadDesign2(),
                        row.getProductName(),
                        row.getVehicleType(),
                        row.getSeasonality(),
                        row.getContentHash(),
                        texts,
                        images,
                        enrichedAt));
    }

    /**
     * The event that closes a re-publication, carrying how many variants it re-emitted.
     *
     * <p>Keyed on the vendor profile, because it describes the profile rather than any one variant.
     * That puts it on a different partition from most of the variants it counts, which is why the
     * consumer's comparison is a report and not an ordering guarantee — see
     * {@link SupplierCatalogRepublishCompletedV1}.
     */
    @NonNull
    static DomainEventEnvelope<SupplierCatalogRepublishCompletedV1> republishCompleted(
            @NonNull UUID vendorProfileId,
            @NonNull String supplierRef,
            int variantCount,
            @NonNull String requestedBy,
            @NonNull Instant completedAt) {
        return new DomainEventEnvelope<>(
                UUIDv7Generator.generate(),
                SupplierCatalogRepublishCompletedV1.EVENT_TYPE,
                SupplierCatalogRepublishCompletedV1.SCHEMA_VERSION,
                vendorProfileId,
                0L,
                completedAt,
                SOURCE,
                // tenantId: stamped by the outbox writer from the bound tenant (ADR-0062 §3)
                null,
                null,
                SOURCE,
                new SupplierCatalogRepublishCompletedV1(
                        vendorProfileId, supplierRef, variantCount, requestedBy, completedAt));
    }
}
