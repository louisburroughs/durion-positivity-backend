package com.positivity.supplier.internal.mktcat.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.adapter.ediwheelc12.EdiwheelC12MktCatCodec;
import com.positivity.supplier.internal.adapter.ediwheelc12.MktCatDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.exception.MktCatImportException;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingests MKCAT marketing enrichment and publishes what changed (CAP-324 #1230, #1257).
 *
 * <h2>One importer, two triggers</h2>
 *
 * A change callback and a scheduled sweep both arrive here. That is what makes the two modes produce
 * identical events -- not a rule anyone remembers to follow, but the fact that there is only one path
 * that can produce one. Two implementations would drift, and the one that drifts is the callback
 * path, which fires rarely and is watched by nobody.
 *
 * <h2>Publication is by content, not by arrival</h2>
 *
 * A variant is published when its content hash differs from the staged one. A catalogue that
 * republishes itself nightly is entirely normal, and treating arrival as change would emit the whole
 * catalogue every night -- which downstream is indistinguishable from every product's marketing copy
 * having actually changed.
 *
 * <h2>A failed image never costs a record</h2>
 *
 * Artwork is fetched and stored before publication, but a fetch or store failure marks that one image
 * unresolved and publishes everything else. Dropping a product's entire marketing copy over one
 * broken download would be a silent loss of the material that did arrive, and the missing image is
 * retried on its own cadence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MktCatImporter {

    private static final String SOURCE = "pos-supplier";

    /** Field separator for hashing. A control character, so it cannot occur in vendor text. */
    private static final char FIELD_SEPARATOR = '\u001f';

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final SupplierMktCatVariantRepository variantRepository;
    private final MktCatImageFetcher imageFetcher;
    private final SupplierOutboxEventWriter outboxEventWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Ingests the catalogue's full variant list.
     *
     * @param supplierRef the catalogue profile to read
     * @return what was seen and how much of it was new or changed
     */
    @NonNull
    public ImportOutcome importAll(@NonNull SupplierRef supplierRef) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.MARKETING_CATALOG);
        EdiwheelC12MktCatCodec codec = resolveCodec(binding);

        SupplierHttpResponse listResponse = exchange(binding, codec.buildVariantListRequest());
        if (!listResponse.isSuccess()) {
            // Thrown rather than reported as an empty catalogue. An importer that diffs would read
            // an empty list as "the vendor withdrew everything".
            throw new MktCatImportException("catalogue variant list could not be read: " + listResponse.outcome()
                    + (listResponse.failureDetail() == null ? "" : " -- " + listResponse.failureDetail()));
        }

        return importVariants(supplierRef, codec.decodeVariantIds(listResponse.body()));
    }

    /**
     * Ingests a specific set of variants, as named by a change callback.
     *
     * <p>The same code as the full sweep, deliberately: see the class note.
     */
    @NonNull
    public ImportOutcome importVariants(@NonNull SupplierRef supplierRef, @NonNull List<String> variantIds) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.MARKETING_CATALOG);
        EdiwheelC12MktCatCodec codec = resolveCodec(binding);
        UUID vendorProfileId = binding.profile().getVendorProfileId();

        int seen = 0;
        int published = 0;
        int skipped = 0;

        for (String variantId : variantIds) {
            seen++;
            try {
                if (importOne(binding, codec, supplierRef, vendorProfileId, variantId)) {
                    published++;
                }
            } catch (MktCatDecodeException | MktCatImportException e) {
                // One unreadable variant must not end the import. The rest of the catalogue is
                // still worth having, and this one is re-attempted on the next sweep.
                skipped++;
                log.warn("Skipping MKCAT variant {} for {}: {}", variantId, supplierRef.value(), e.getMessage());
            }
        }

        log.info(
                "MKCAT import for {}: {} seen, {} published, {} skipped",
                supplierRef.value(),
                seen,
                published,
                skipped);
        return new ImportOutcome(seen, published, skipped);
    }

    /** @return whether this variant's content had changed and was therefore published */
    private boolean importOne(
            @NonNull ResolvedBinding binding,
            @NonNull EdiwheelC12MktCatCodec codec,
            @NonNull SupplierRef supplierRef,
            @NonNull UUID vendorProfileId,
            @NonNull String variantId) {
        String detail = bodyOrThrow(exchange(binding, codec.buildVariantDetailRequest(variantId)), "variant detail");
        // Images and language data are optional: a variant with neither is still a variant, and
        // refusing it would lose the design entirely over its lack of a picture.
        String images = bodyOrNull(exchange(binding, codec.buildVariantImagesRequest(variantId)));
        String languages = bodyOrNull(exchange(binding, codec.buildVariantLanguageDataRequest(variantId)));

        MarketingVariant variant = codec.decodeVariant(variantId, detail, images, languages);
        List<SupplierCatalogEnrichmentImage> storedImages = imageFetcher.fetchAndStore(variant.imageUris());
        List<SupplierCatalogEnrichmentText> texts = variant.texts().stream()
                .map(text -> new SupplierCatalogEnrichmentText(
                        text.languageCode(), text.name(), text.description(), text.footNotes()))
                .toList();

        return stageAndPublish(
                vendorProfileId, supplierRef, variant, texts, storedImages, hashOf(variant, storedImages));
    }

    /**
     * Stages the variant and publishes it when its content changed.
     *
     * <p>Both in one transaction, through the outbox: a variant recorded as published that was never
     * emitted would be skipped on every later run, because its hash now matches (ADR-0044 section 4).
     */
    @Transactional
    public boolean stageAndPublish(
            @NonNull UUID vendorProfileId,
            @NonNull SupplierRef supplierRef,
            @NonNull MarketingVariant variant,
            @NonNull List<SupplierCatalogEnrichmentText> texts,
            @NonNull List<SupplierCatalogEnrichmentImage> images,
            @NonNull String contentHash) {
        Instant now = Instant.now(clock);
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

        outboxEventWriter.publish(
                DomainTopics.events("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        SupplierCatalogUpdatedV1.EVENT_TYPE,
                        SupplierCatalogUpdatedV1.SCHEMA_VERSION,
                        // Keyed on the staged row, so every enrichment for one variant lands on one
                        // partition in order: a stale republication overtaking a newer one would put
                        // withdrawn marketing copy back on a product.
                        saved.getSupplierMktCatVariantId(),
                        0L,
                        now,
                        SOURCE,
                        null,
                        SOURCE,
                        new SupplierCatalogUpdatedV1(
                                vendorProfileId,
                                supplierRef.value(),
                                variant.vendorVariantId(),
                                variant.brand(),
                                variant.treadDesign(),
                                variant.treadDesign2(),
                                variant.productName(),
                                variant.vehicleType(),
                                variant.seasonality(),
                                contentHash,
                                texts,
                                images,
                                now)));
        return true;
    }

    /**
     * The hash that decides whether anything changed.
     *
     * <p>Covers exactly what gets published, image content hashes included -- so replaced artwork at
     * an unchanged URL counts as a change, and unchanged artwork republished at a new URL does not.
     * Unresolved images are included as unresolved, which means a variant whose artwork is still
     * missing keeps a different hash from the same variant once the artwork arrives, and is
     * republished when it does.
     */
    @NonNull
    private String hashOf(@NonNull MarketingVariant variant, @NonNull List<SupplierCatalogEnrichmentImage> images) {
        StringBuilder material = new StringBuilder();
        append(material, variant.vendorVariantId());
        append(material, variant.brand());
        append(material, variant.treadDesign());
        append(material, variant.treadDesign2());
        append(material, variant.productName());
        append(material, variant.vehicleType());
        append(material, variant.seasonality());
        for (MarketingVariant.MarketingText text : variant.texts()) {
            append(material, text.languageCode());
            append(material, text.name());
            append(material, text.description());
            append(material, text.footNotes());
        }
        for (SupplierCatalogEnrichmentImage image : images) {
            append(material, image.imageType());
            append(material, image.contentHash());
            append(material, String.valueOf(image.unresolved()));
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Appends one field and its separator.
     *
     * <p>Separated rather than concatenated so two different variants cannot hash alike: a brand of
     * "Michelin" with design "X" and a brand of "MichelinX" with no design would otherwise produce
     * identical material, and one would silently never be republished.
     */
    private static void append(@NonNull StringBuilder material, @Nullable String value) {
        material.append(value == null ? "" : value).append(FIELD_SEPARATOR);
    }

    @NonNull
    private SupplierHttpResponse exchange(@NonNull ResolvedBinding binding, @NonNull SupplierRequestSpec spec) {
        return baseClient.exchange(new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent(),
                spec.headers()));
    }

    @NonNull
    private static String bodyOrThrow(@NonNull SupplierHttpResponse response, @NonNull String what) {
        if (!response.isSuccess() || response.body() == null) {
            throw new MktCatImportException(what + " could not be read: " + response.outcome());
        }
        return response.body();
    }

    @Nullable
    private static String bodyOrNull(@NonNull SupplierHttpResponse response) {
        return response.isSuccess() ? response.body() : null;
    }

    @NonNull
    private EdiwheelC12MktCatCodec resolveCodec(@NonNull ResolvedBinding binding) {
        AdapterResolution resolution =
                adapterRegistry.resolve(SupplierCapability.MARKETING_CATALOG, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved
                && resolved.codec() instanceof EdiwheelC12MktCatCodec codec) {
            return codec;
        }
        throw new SupplierConfigurationException(
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                "No marketing catalogue codec registered for " + binding.family() + " "
                        + binding.version().value());
    }

    /**
     * What one import run did.
     *
     * @param seen      variants the catalogue offered
     * @param published variants whose content had changed and were emitted
     * @param skipped   variants that could not be read this run and will be re-attempted
     */
    public record ImportOutcome(int seen, int published, int skipped) {}
}
