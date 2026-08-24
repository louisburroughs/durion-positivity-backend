package com.positivity.supplier.internal.adapter.ediwheelc12;

import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * EDIWheel C1.2 MKCAT marketing catalogue codec (CAP-324 #1230).
 *
 * <h2>Four reads make one variant</h2>
 *
 * The catalogue splits a tread design variant across separate resources: a list, a detail, its
 * images and its language-dependent data. This codec builds and decodes each; assembling them into
 * one enrichment is the importer's job, because the assembly involves fetching artwork and that is
 * I/O a codec must not do (ADR-0051 §5).
 *
 * <h2>An unreadable catalogue is not an empty one</h2>
 *
 * Every decode raises rather than returning an empty list. The importer diffs what it fetched
 * against what it holds, so "the vendor published nothing" and "we could not read the response"
 * would otherwise reach the same conclusion — that every variant previously held has been withdrawn.
 */
@Component
@RequiredArgsConstructor
public class EdiwheelC12MktCatCodec implements SupplierAdapterCodec {
    private static final String APPLICATION_JSON = "application/json";

    private static final String TREAD_DESIGN_VARIANTS_PATH = "/treadDesignVariants/";

    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.MARKETING_CATALOG;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_JSON;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.C1_2;
    }

    /** Lists every tread design variant the catalogue publishes. */
    @NonNull
    public SupplierRequestSpec buildVariantListRequest() {
        return new SupplierRequestSpec("GET", TREAD_DESIGN_VARIANTS_PATH, Map.of(), null, null, APPLICATION_JSON, true);
    }

    /** Reads one variant's detail. */
    @NonNull
    public SupplierRequestSpec buildVariantDetailRequest(@NonNull String variantId) {
        return new SupplierRequestSpec(
                "GET",
                TREAD_DESIGN_VARIANTS_PATH + encode(variantId) + "/",
                Map.of(),
                null,
                null,
                APPLICATION_JSON,
                true);
    }

    /** Reads one variant's artwork listing. */
    @NonNull
    public SupplierRequestSpec buildVariantImagesRequest(@NonNull String variantId) {
        return new SupplierRequestSpec(
                "GET",
                TREAD_DESIGN_VARIANTS_PATH + encode(variantId) + "/images",
                Map.of(),
                null,
                null,
                APPLICATION_JSON,
                true);
    }

    /** Reads one variant's language-dependent marketing copy. */
    @NonNull
    public SupplierRequestSpec buildVariantLanguageDataRequest(@NonNull String variantId) {
        return new SupplierRequestSpec(
                "GET",
                TREAD_DESIGN_VARIANTS_PATH + encode(variantId) + "/languageDependentData",
                Map.of(),
                null,
                null,
                APPLICATION_JSON,
                true);
    }

    /**
     * Subscribes to catalogue change notifications.
     *
     * <p>Not idempotent: the catalogue records a subscription per call, so a blind re-send after an
     * ambiguous failure risks a second one and therefore duplicate callbacks. The subscription state
     * is tracked on this side and reconciled rather than re-sent.
     */
    @NonNull
    public SupplierRequestSpec buildSubscribeRequest(@NonNull String callbackUrl, @NonNull String event) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("callbackUrl", callbackUrl);
        query.put("event", event);
        return new SupplierRequestSpec(
                "POST", "/treadDesignVariants/subscribe/", query, "{}", APPLICATION_JSON, APPLICATION_JSON, false);
    }

    /** Cancels a change subscription. */
    @NonNull
    public SupplierRequestSpec buildUnsubscribeRequest(@NonNull String callbackUrl, @NonNull String event) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("callbackUrl", callbackUrl);
        query.put("event", event);
        return new SupplierRequestSpec(
                "DELETE", "/treadDesignVariants/subscribe/", query, null, null, APPLICATION_JSON, true);
    }

    /** Lists supplier master data. */
    @NonNull
    public SupplierRequestSpec buildSupplierListRequest() {
        return new SupplierRequestSpec("GET", "/suppliers/", Map.of(), null, null, APPLICATION_JSON, true);
    }

    /**
     * Decodes the variant list into the catalogue's own identifiers.
     *
     * @return every variant id the catalogue published, in the order it published them
     * @throws MktCatDecodeException when the body is not a readable variant list
     */
    @NonNull
    public List<String> decodeVariantIds(@Nullable String body) {
        List<MktCatWire.VariantListEntry> entries =
                readList(body, new TypeReference<List<MktCatWire.VariantListEntry>>() {}, "tread design variant list");
        List<String> ids = new ArrayList<>(entries.size());
        for (MktCatWire.VariantListEntry entry : entries) {
            if (entry.TreadDesignVariant() != null
                    && !entry.TreadDesignVariant().isBlank()) {
                ids.add(entry.TreadDesignVariant().trim());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Assembles one variant from its detail, images and language data.
     *
     * @param variantId       the catalogue id being assembled, carried through rather than taken
     *                        from the response: a detail body that echoes a different id is a vendor
     *                        defect, and adopting it would attach this variant's copy to another
     * @param detailBody      body of the detail read
     * @param imagesBody      body of the images read; may be absent when the variant has none
     * @param languageBody    body of the language-data read; may be absent
     * @throws MktCatDecodeException when the detail cannot be read
     */
    @NonNull
    public MarketingVariant decodeVariant(
            @NonNull String variantId,
            @Nullable String detailBody,
            @Nullable String imagesBody,
            @Nullable String languageBody) {
        MktCatWire.VariantDetail detail = read(detailBody, MktCatWire.VariantDetail.class, "tread design variant");

        return new MarketingVariant(
                variantId,
                trimToNull(detail.Brand()),
                trimToNull(detail.TreadDesign1()),
                trimToNull(detail.TreadDesign2()),
                trimToNull(detail.ProductName()),
                trimToNull(detail.VehicleType()),
                trimToNull(detail.Seasonality()),
                decodeTexts(languageBody),
                decodeImageRefs(imagesBody, languageBody));
    }

    /**
     * Reads marketing copy per language.
     *
     * <p>An absent or unreadable language body yields no texts rather than raising. Marketing copy
     * is the enrichment's most replaceable part: a variant with artwork and no description is still
     * worth publishing, and failing the whole import over it would lose the rest.
     */
    @NonNull
    private List<MarketingVariant.MarketingText> decodeTexts(@Nullable String languageBody) {
        if (languageBody == null || languageBody.isBlank()) {
            return List.of();
        }
        List<MktCatWire.LanguageEntry> entries;
        try {
            entries = objectMapper.readValue(languageBody, new TypeReference<List<MktCatWire.LanguageEntry>>() {});
        } catch (RuntimeException e) {
            return List.of();
        }
        List<MarketingVariant.MarketingText> texts = new ArrayList<>();
        for (MktCatWire.LanguageEntry entry : entries) {
            String language = trimToNull(entry.LanguageID() != null ? entry.LanguageID() : entry.Language());
            if (language == null) {
                // Copy nobody can say the language of cannot be shown to anybody: a shop would have
                // no way to know whether it is reading German marketing text to a French customer.
                continue;
            }
            texts.add(new MarketingVariant.MarketingText(
                    language,
                    trimToNull(entry.ProductName()),
                    trimToNull(entry.Description()),
                    trimToNull(entry.FootNotes())));
        }
        return List.copyOf(texts);
    }

    /**
     * Collects every artwork URI the catalogue offers for a variant.
     *
     * <p>From both places it publishes them: the variant's own images resource, and the additional
     * graphics attached to language-dependent data. Reading only the first would silently drop the
     * per-market artwork, which is exactly the material a marketing catalogue exists to supply.
     */
    @NonNull
    private List<MarketingVariant.MarketingImageRef> decodeImageRefs(
            @Nullable String imagesBody, @Nullable String languageBody) {
        Map<String, MarketingVariant.MarketingImageRef> byUri = new LinkedHashMap<>();

        if (imagesBody != null && !imagesBody.isBlank()) {
            try {
                for (MktCatWire.ImageEntry entry : objectMapper.<List<MktCatWire.ImageEntry>>readValue(
                        imagesBody, new TypeReference<List<MktCatWire.ImageEntry>>() {})) {
                    String uri = trimToNull(entry.URI());
                    if (uri != null) {
                        byUri.putIfAbsent(
                                uri, new MarketingVariant.MarketingImageRef(trimToNull(entry.ImageType()), uri));
                    }
                }
            } catch (RuntimeException e) {
                throw new MktCatDecodeException("variant image list was not readable JSON: " + e.getMessage(), e);
            }
        }

        if (languageBody != null && !languageBody.isBlank()) {
            try {
                for (MktCatWire.LanguageEntry entry : objectMapper.<List<MktCatWire.LanguageEntry>>readValue(
                        languageBody, new TypeReference<List<MktCatWire.LanguageEntry>>() {})) {
                    if (entry.AdditionalGraphic() == null) {
                        continue;
                    }
                    for (MktCatWire.AdditionalGraphic graphic : entry.AdditionalGraphic()) {
                        String uri = trimToNull(graphic.URI());
                        if (uri != null) {
                            byUri.putIfAbsent(
                                    uri, new MarketingVariant.MarketingImageRef(trimToNull(graphic.Graphic()), uri));
                        }
                    }
                }
            } catch (RuntimeException e) {
                // The language body is optional and already tolerated above; a graphic list that
                // will not parse costs those graphics, not the variant.
                return List.copyOf(byUri.values());
            }
        }

        // Deduplicated by URI: the same picture is routinely listed against several languages, and
        // fetching it once per language would multiply every ingest by the number of markets.
        return List.copyOf(byUri.values());
    }

    /** Decodes supplier master data into the catalogue's party identifiers and names. */
    @NonNull
    public Map<String, String> decodeSuppliers(@Nullable String body) {
        List<MktCatWire.SupplierEntry> entries =
                readList(body, new TypeReference<List<MktCatWire.SupplierEntry>>() {}, "supplier list");
        Map<String, String> byPartyId = new LinkedHashMap<>();
        for (MktCatWire.SupplierEntry entry : entries) {
            String partyId = trimToNull(entry.PartyID());
            if (partyId == null) {
                continue;
            }
            String name = null;
            if (entry.Name() != null && !entry.Name().isEmpty()) {
                name = trimToNull(entry.Name().getFirst().Name());
            }
            byPartyId.put(partyId, name == null ? partyId : name);
        }
        return Map.copyOf(byPartyId);
    }

    @NonNull
    private <T> T read(@Nullable String body, @NonNull Class<T> type, @NonNull String what) {
        if (body == null || body.isBlank()) {
            throw new MktCatDecodeException(what + " response body was empty");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (RuntimeException e) {
            throw new MktCatDecodeException(what + " response was not readable JSON: " + e.getMessage(), e);
        }
    }

    @NonNull
    private <T> List<T> readList(@Nullable String body, @NonNull TypeReference<List<T>> type, @NonNull String what) {
        if (body == null || body.isBlank()) {
            throw new MktCatDecodeException(what + " response body was empty");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (RuntimeException e) {
            throw new MktCatDecodeException(what + " response was not readable JSON: " + e.getMessage(), e);
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Percent-encodes a path segment so a catalogue id cannot alter the path it sits in. */
    @NonNull
    private static String encode(@NonNull String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
