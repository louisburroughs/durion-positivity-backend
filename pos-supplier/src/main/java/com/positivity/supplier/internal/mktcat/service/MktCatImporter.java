package com.positivity.supplier.internal.mktcat.service;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.supplier.internal.adapter.ediwheelc12.EdiwheelC12MktCatCodec;
import com.positivity.supplier.internal.adapter.ediwheelc12.MktCatDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.exception.MktCatImportException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.SupplierCatalogPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

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
public class MktCatImporter implements SupplierCatalogPort {

    /** Field separator for hashing. A control character, so it cannot occur in vendor text. */
    private static final char FIELD_SEPARATOR = '\u001f';

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final MktCatImageFetcher imageFetcher;
    private final MktCatVariantStager variantStager;

    /**
     * Ingests the catalogue's full variant list.
     *
     * @param supplierRef the catalogue profile to read
     * @return what was seen and how much of it was new or changed
     */
    @NonNull
    public ImportOutcome importAll(@NonNull SupplierRef supplierRef) {
        return importVariants(supplierRef, listVariantIds(supplierRef));
    }

    /**
     * Ingests a specific set of variants, as named by a change callback.
     *
     * <p>The same code as the full sweep, deliberately: see the class note.
     */
    @NonNull
    public ImportOutcome importVariants(@NonNull SupplierRef supplierRef, @NonNull List<String> variantIds) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.MARKETING_CATALOG);
        EdiwheelC12MktCatCodec codec = SupplierCodecs.require(
                adapterRegistry, binding, SupplierCapability.MARKETING_CATALOG, EdiwheelC12MktCatCodec.class);
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
        MarketingVariant variant = readVariant(binding, codec, variantId);
        List<SupplierCatalogEnrichmentImage> storedImages = imageFetcher.fetchAndStore(variant.imageUris());
        List<SupplierCatalogEnrichmentText> texts = variant.texts().stream()
                .map(text -> new SupplierCatalogEnrichmentText(
                        text.languageCode(), text.name(), text.description(), text.footNotes()))
                .toList();

        return variantStager.stageAndPublish(
                vendorProfileId, supplierRef, variant, texts, storedImages, hashOf(variant, storedImages));
    }

    /**
     * Reads one variant from the catalogue's four resources, without storing anything.
     *
     * <p>Shared by the scheduled import and the {@link SupplierCatalogPort}, so a caller that wants
     * only the vendor's view gets exactly what the importer saw. Two readers would drift, and the
     * one that drifts is the port — used rarely, watched by nobody.
     */
    @NonNull
    private MarketingVariant readVariant(
            @NonNull ResolvedBinding binding, @NonNull EdiwheelC12MktCatCodec codec, @NonNull String variantId) {
        String detail = bodyOrThrow(exchange(binding, codec.buildVariantDetailRequest(variantId)), "variant detail");
        // Images and language data are optional: a variant with neither is still a variant, and
        // refusing it would lose the design entirely over its lack of a picture.
        String images = bodyOrNull(exchange(binding, codec.buildVariantImagesRequest(variantId)));
        String languages = bodyOrNull(exchange(binding, codec.buildVariantLanguageDataRequest(variantId)));
        return codec.decodeVariant(variantId, detail, images, languages);
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
        String body = response.body();
        if (!response.isSuccess() || body == null) {
            throw new MktCatImportException(what + " could not be read: " + response.outcome());
        }
        return body;
    }

    @Nullable
    private static String bodyOrNull(@NonNull SupplierHttpResponse response) {
        return response.isSuccess() ? response.body() : null;
    }
    /**
     * What one import run did.
     *
     * @param seen      variants the catalogue offered
     * @param published variants whose content had changed and were emitted
     * @param skipped   variants that could not be read this run and will be re-attempted
     */
    public record ImportOutcome(int seen, int published, int skipped) {}

    /**
     * Lists every variant the catalogue publishes (the {@link SupplierCatalogPort}).
     *
     * <p>Throws rather than answering with an empty list, for the reason the whole importer is built
     * around: it diffs what it fetched against what it holds, and an unreadable catalogue read as an
     * empty one concludes that every variant ever published has been withdrawn.
     */
    @Override
    @NonNull
    public List<String> listVariantIds(@NonNull SupplierRef supplierRef) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.MARKETING_CATALOG);
        EdiwheelC12MktCatCodec codec = SupplierCodecs.require(
                adapterRegistry, binding, SupplierCapability.MARKETING_CATALOG, EdiwheelC12MktCatCodec.class);

        SupplierHttpResponse response = exchange(binding, codec.buildVariantListRequest());
        if (!response.isSuccess()) {
            throw new MktCatImportException("catalogue variant list could not be read: " + response.outcome()
                    + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }
        return codec.decodeVariantIds(response.body());
    }

    /**
     * Assembles one variant from the catalogue's four resources (the {@link SupplierCatalogPort}).
     *
     * <p>Returns artwork as the URIs the catalogue published. Fetching those bytes is deliberately
     * not part of this: it is I/O against arbitrary asset hosts rather than the configured vendor
     * endpoint, and doing it behind the port would send vendor credentials to whatever host the
     * catalogue named.
     */
    @Override
    @NonNull
    public MarketingVariant fetchVariant(@NonNull SupplierRef supplierRef, @NonNull String vendorVariantId) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.MARKETING_CATALOG);
        EdiwheelC12MktCatCodec codec = SupplierCodecs.require(
                adapterRegistry, binding, SupplierCapability.MARKETING_CATALOG, EdiwheelC12MktCatCodec.class);
        return readVariant(binding, codec, vendorVariantId);
    }
}
