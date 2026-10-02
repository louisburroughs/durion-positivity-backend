package com.positivity.supplier.internal.mktcat.service;

import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishRequestedV1;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Re-emits every MKCAT variant staged for a vendor profile when asked to (ADR-0044 §4, #2356).
 *
 * <h2>Why the owner re-emits rather than anything else recovering</h2>
 *
 * An enrichment a consumer lost cannot come back by any other route. A replay of the original event
 * carries the event id the consumer already recorded, so its guard skips it again. A fresh import
 * publishes nothing, because {@link MktCatVariantStager} publishes on content change and the staged
 * hash still matches. And the consumer cannot read the staged rows: ADR-0044 R1 forbids the
 * synchronous call, R6 makes this module the only owner of what the vendor sent. So recovery is a
 * request on the owner's command topic and a re-publication on the owner's event topic, the same
 * shape as {@code PriceCatalogRepublisher}.
 *
 * <h2>The hash guard is bypassed, not changed</h2>
 *
 * This class never goes near {@link MktCatVariantStager#stageAndPublish}. It reads the staged rows
 * and publishes each one as it stands — stored {@code contentHash}, stored texts and images — under
 * a new event id. Nothing is written back, so an ordinary import after a re-publication still sees
 * the hash it stored and still publishes nothing for an unchanged variant.
 *
 * <h2>Over-broad on purpose, and unbounded on purpose</h2>
 *
 * The request names the vendor profile, not the missing variants, because nobody knows which ones
 * were lost. Unlike a PRICAT re-publication there is no cooldown and no attempt cap: no consumer
 * asks for this automatically, so there is no loop to bound, and the state a cap would need would be
 * a schema change for a command an operator sends by hand. A redelivery of the same command is
 * stopped by the listener's event-id guard; a <em>second</em> command re-emits everything again.
 * That is acceptable only because the consumer is idempotent on content — pos-catalog treats an
 * unchanged {@code contentHash} for a design it holds as a no-op — so a repeat costs topic volume
 * and changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MktCatRepublisher {

    private static final TypeReference<List<SupplierCatalogEnrichmentText>> TEXTS = new TypeReference<>() {};
    private static final TypeReference<List<SupplierCatalogEnrichmentImage>> IMAGES = new TypeReference<>() {};

    private final SupplierMktCatVariantRepository variantRepository;
    private final SupplierOutboxEventWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** How many staged variants are read per page while re-emitting. */
    @Value("${pos.supplier.mktcat.republish-page-size:200}")
    private int pageSize;

    /**
     * Re-emits the vendor profile's staged variants, followed by a completion event carrying their
     * count.
     *
     * @return the number of variant events queued; {@code 0} when nothing is staged for the profile,
     *         which is logged
     */
    @Transactional
    public int republish(@NonNull SupplierCatalogRepublishRequestedV1 request) {
        String topic = DomainTopics.events("supplier");
        Instant now = Instant.now(clock);
        int emitted = 0;
        String supplierRef = null;

        // Read and queued a page at a time: a manufacturer's catalogue can run to thousands of designs,
        // each with its marketing copy, and holding all of them to re-emit a recovery would make the
        // recovery itself the outage.
        List<SupplierMktCatVariantEntity> page;
        int pageNumber = 0;
        do {
            page = variantRepository.findByVendorProfileIdOrderBySupplierMktCatVariantIdAsc(
                    request.vendorProfileId(), PageRequest.of(pageNumber++, pageSize));
            for (SupplierMktCatVariantEntity row : page) {
                outboxWriter.publish(
                        topic,
                        MktCatEventFactory.variantUpdated(row, texts(row), images(row), enrichedAt(row, now), now));
                supplierRef = row.getSupplierRef();
                emitted++;
            }
        } while (page.size() == pageSize);

        if (supplierRef == null) {
            // Not retryable and not this module's defect to fix: the request names a profile with no
            // marketing catalogue staged in this tenant. Recording it and moving on beats blocking
            // the partition. No completion event either — a count of zero for a profile that was
            // never imported would read downstream as a catalogue that had been emptied.
            log.error(
                    "Cannot re-publish MKCAT variants for vendor profile {} requested by {}: nothing is staged",
                    request.vendorProfileId(),
                    request.requestedBy());
            return 0;
        }

        outboxWriter.publish(
                topic,
                MktCatEventFactory.republishCompleted(
                        request.vendorProfileId(), supplierRef, emitted, request.requestedBy(), now));

        log.warn(
                "Re-published {} MKCAT variants of vendor profile {} ({}) for {}: {}",
                emitted,
                request.vendorProfileId(),
                supplierRef,
                request.requestedBy(),
                request.reason());
        return emitted;
    }

    /**
     * When the enrichment being re-delivered was fetched: the instant the row was last published,
     * not this one. A re-emit re-delivers a fact, it does not restate when the fact happened.
     */
    private static Instant enrichedAt(SupplierMktCatVariantEntity row, Instant fallback) {
        return row.getLastPublishedAt() == null ? fallback : row.getLastPublishedAt();
    }

    /**
     * The texts as they were published.
     *
     * <p>The staged JSON is the record of what went out, so the re-emitted enrichment is the
     * original one rather than a fresh reading of the vendor's catalogue — which is why no vendor
     * call is made here, and why the hash the consumer compares still describes the content.
     */
    private List<SupplierCatalogEnrichmentText> texts(SupplierMktCatVariantEntity row) {
        try {
            return objectMapper.readValue(row.getTextsJson(), TEXTS);
        } catch (JacksonException e) {
            throw unreadable(row, "texts_json", e);
        }
    }

    private List<SupplierCatalogEnrichmentImage> images(SupplierMktCatVariantEntity row) {
        try {
            return objectMapper.readValue(row.getImagesJson(), IMAGES);
        } catch (JacksonException e) {
            throw unreadable(row, "images_json", e);
        }
    }

    /**
     * This module wrote that JSON, so failing to read it back is its own inconsistent state and not
     * a defect in the command. Thrown as {@link IllegalStateException} so the listener reports it as
     * that and the whole re-publication rolls back: skipping the row would re-emit a catalogue that
     * looks whole to the consumer while a design stayed missing.
     */
    private static IllegalStateException unreadable(SupplierMktCatVariantEntity row, String column, Exception cause) {
        return new IllegalStateException(
                "Staged MKCAT variant " + row.getSupplierMktCatVariantId() + " has unreadable " + column, cause);
    }
}
