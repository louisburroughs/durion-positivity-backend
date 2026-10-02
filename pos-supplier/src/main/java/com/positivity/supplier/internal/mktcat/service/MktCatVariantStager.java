package com.positivity.supplier.internal.mktcat.service;

import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The staging write and the outbox emit for one MKCAT variant, as a single transaction.
 *
 * <h2>Why this is its own bean</h2>
 *
 * {@link MktCatImporter} used to hold this method and call it on {@code this}. Spring's transaction
 * advice is proxy-based, so a self-call bypasses it entirely and the {@code @Transactional} below was
 * never applied — the staged row committed on its own and the outbox write was a separate unit. That
 * is exactly the interleaving the note on {@link #stageAndPublish} warns about: a variant recorded as
 * published whose event was never emitted is skipped on every later run, because its hash now matches.
 * Living in a separate bean means the call crosses the proxy and the boundary is real.
 */
@Service
@RequiredArgsConstructor
public class MktCatVariantStager {

    private final SupplierMktCatVariantRepository variantRepository;
    private final SupplierOutboxEventWriter outboxEventWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Stages the variant and publishes it when its content changed.
     *
     * <p>Both in one transaction, through the outbox: a variant recorded as published that was never
     * emitted would be skipped on every later run, because its hash now matches (ADR-0044 section 4).
     *
     * @return whether this variant's content had changed and was therefore published
     */
    @Transactional
    public boolean stageAndPublish(
            @NonNull UUID vendorProfileId,
            @NonNull SupplierRef supplierRef,
            @NonNull MarketingVariant variant,
            @NonNull List<SupplierCatalogEnrichmentText> texts,
            @NonNull List<SupplierCatalogEnrichmentImage> images,
            @NonNull String contentHash) {
        // Cut to the precision the staged row keeps (timestamp(6)). The event below states this
        // instant and a re-publication later reads it back from the row (MktCatRepublisher, #2356); a
        // clock that resolves finer than a microsecond would otherwise publish one instant and store
        // another, and the re-emitted enrichment would not be the one that was published.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Optional<SupplierMktCatVariantEntity> existing =
                variantRepository.findByVendorProfileIdAndVendorVariantId(vendorProfileId, variant.vendorVariantId());

        if (existing.isPresent() && contentHash.equals(existing.get().getContentHash())) {
            // Seen, not changed. Recorded so an operator can tell "the catalogue stopped sending
            // this" from "the catalogue keeps sending it unchanged" -- which look identical if only
            // changes are timestamped.
            SupplierMktCatVariantEntity row = existing.get();
            row.setLastSeenAt(now);
            variantRepository.save(row);
            return false;
        }

        SupplierMktCatVariantEntity row = existing.orElseGet(() -> SupplierMktCatVariantEntity.builder()
                .vendorProfileId(vendorProfileId)
                .vendorVariantId(variant.vendorVariantId())
                .firstSeenAt(now)
                .build());

        row.setSupplierRef(supplierRef.value());
        row.setBrand(variant.brand());
        row.setTreadDesign(variant.treadDesign());
        row.setTreadDesign2(variant.treadDesign2());
        row.setProductName(variant.productName());
        row.setVehicleType(variant.vehicleType());
        row.setSeasonality(variant.seasonality());
        row.setContentHash(contentHash);
        row.setTextsJson(objectMapper.writeValueAsString(texts));
        row.setImagesJson(objectMapper.writeValueAsString(images));
        row.setHasUnresolvedImages(images.stream().anyMatch(SupplierCatalogEnrichmentImage::unresolved));
        row.setLastSeenAt(now);
        row.setLastPublishedAt(now);
        SupplierMktCatVariantEntity saved = variantRepository.save(row);

        // Read off the staged row rather than the vendor document, so this publication and a later
        // re-publication of the same row (MktCatRepublisher, #2356) cannot describe it differently.
        outboxEventWriter.publish(
                DomainTopics.events("supplier"), MktCatEventFactory.variantUpdated(saved, texts, images, now, now));
        return true;
    }
}
