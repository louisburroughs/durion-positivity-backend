package com.positivity.supplier.internal.adapter.ediwheelb;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * EDIWheel PRICAT B4.0 codec: {@code GET /catalog/B4_0/pricat} (CAP-318 #1224).
 *
 * <p>Registered in the adapter registry under {@code (PRICE_CATALOG, EDIWHEEL_B, B4_0)}
 * (ADR-0051 §3). It performs no I/O — it builds a request for the shared base client and maps a
 * response body into canonical entries (ADR-0051 §5).
 *
 * <h2>Effective dating</h2>
 *
 * B4.0 states validity per price: {@code netValueValidFrom} for the net price and
 * {@code grossPriceValidFrom} for the gross price, both {@code yyyyMMdd}. The entry's
 * {@code effectiveFrom} is the net date when a net value is present, else the gross date, else the
 * document date. Vendor dates are vendor-local calendar dates and are never converted to instants
 * (ADR-0053 §2).
 *
 * <h2>What is deliberately not read</h2>
 *
 * The eighty-odd descriptive tyre attributes (dimensions, labels, tread design). A price catalog is
 * a price fact; product enrichment is MKCAT's job (#1230). Reading them here would silently make
 * pos-supplier a second product-master source.
 */
@Component
@RequiredArgsConstructor
public class EdiwheelB40PricatCodec implements SupplierAdapterCodec {

    /** Vendor validity dates: {@code yyyyMMdd}. */
    private static final DateTimeFormatter VENDOR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Envelope header date: {@code yyyyMMddHHmm} in the vendor's own examples. */
    private static final DateTimeFormatter VENDOR_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.PRICE_CATALOG;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_B;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.B4_0;
    }

    /**
     * Builds the catalog fetch for one buyer account.
     *
     * <p>The spec is marked idempotent: PRICAT is a read bounded by the buyer account and market,
     * and a repeated fetch produces another import manifest rather than a second commercial effect
     * (ADR-0052 §5).
     *
     * @param partyContext buyer identity; {@code billingAccountNumber} is the vendor's
     *                     {@code buyerParty} and {@code billingAgencyCode} its {@code agencyCode}
     * @param eanFilter    optional single-EAN filter the norm supports; null fetches the full catalog
     * @return a transport-free request spec for the orchestration layer to dispatch
     */
    @NonNull
    public SupplierRequestSpec buildRequest(@NonNull PartyContext partyContext, @Nullable String eanFilter) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("buyerParty", partyContext.billingAccountNumber());
        if (partyContext.billingAgencyCode() != null
                && !partyContext.billingAgencyCode().isBlank()) {
            query.put("agencyCode", partyContext.billingAgencyCode());
        }
        if (eanFilter != null && !eanFilter.isBlank()) {
            query.put("ean", eanFilter.trim());
        }
        return new SupplierRequestSpec("GET", null, query, null, null, "application/json", true);
    }

    /**
     * Decodes a PRICAT response body into canonical entries plus the lines that could not be
     * decoded.
     *
     * @param body             response body as received
     * @return the decoded document; never null, and never partially silent
     * @throws PricatDecodeException when the body is not a PRICAT document at all, or the vendor
     *                               signalled a document-level error code
     */
    @NonNull
    public PricatDocument decode(@Nullable String body) {
        PricatB40Wire.Catalog catalog = parseDocument(body);
        PricatB40Wire.EnvelopeHeader header = catalog.envelopeHeader();
        requireNoVendorError(header);

        String documentId = header == null ? null : trimToNull(header.documentId());
        LocalDate documentDate = header == null ? null : parseHeaderDate(header.date());
        String countryCode = header == null ? null : trimToNull(header.countryCode());
        String currency = header == null ? null : trimToNull(header.currency());

        List<PricatB40Wire.Article> articles = catalog.articles() == null ? List.of() : catalog.articles();
        DecodedArticles decoded = decodeArticles(articles, documentId, documentDate, countryCode, currency);

        return new PricatDocument(
                documentId,
                documentDate,
                countryCode,
                currency,
                decoded.entries(),
                decoded.rejected(),
                articles.size());
    }

    /**
     * Parses the raw body into a document, or fails with the specific reason a caller needs to
     * distinguish an empty response from a malformed one from a syntactically valid non-document.
     */
    private PricatB40Wire.@NonNull Catalog parseDocument(@Nullable String body) {
        if (body == null || body.isBlank()) {
            throw new PricatDecodeException("PRICAT response body was empty");
        }
        PricatB40Wire.Catalog catalog;
        try {
            catalog = objectMapper.readValue(body, PricatB40Wire.Catalog.class);
        } catch (Exception e) {
            throw new PricatDecodeException("PRICAT response body is not a B4.0 catalog document", e);
        }
        if (catalog == null) {
            throw new PricatDecodeException("PRICAT response body decoded to no document");
        }
        return catalog;
    }

    /**
     * A document-level error means the vendor answered with a rejection, not a catalog. Treating it
     * as an empty catalog would let a vendor-side failure look like "no prices".
     */
    private void requireNoVendorError(PricatB40Wire.@Nullable EnvelopeHeader header) {
        String errorCode = header == null ? null : trimToNull(header.errorCode());
        if (errorCode != null && !"0".equals(errorCode)) {
            throw new PricatDecodeException("PRICAT document carries vendor error code " + errorCode);
        }
    }

    /** The article lines split into what mapped cleanly and what a line-level failure rejected. */
    private record DecodedArticles(
            List<SupplierPriceCatalogEntry> entries, List<PricatDocument.RejectedLine> rejected) {}

    private DecodedArticles decodeArticles(
            List<PricatB40Wire.Article> articles,
            String documentId,
            LocalDate documentDate,
            String countryCode,
            String currency) {
        List<SupplierPriceCatalogEntry> entries = new ArrayList<>(articles.size());
        List<PricatDocument.RejectedLine> rejected = new ArrayList<>();

        for (PricatB40Wire.Article article : articles) {
            if (article == null) {
                rejected.add(new PricatDocument.RejectedLine(null, null, null, null, "null article line"));
                continue;
            }
            try {
                entries.add(toEntry(article, documentId, documentDate, countryCode, currency));
            } catch (IllegalArgumentException e) {
                rejected.add(new PricatDocument.RejectedLine(
                        article.pos(),
                        trimToNull(article.ean()),
                        trimToNull(article.supplierCode()),
                        trimToNull(article.xReferenceCode()),
                        e.getMessage()));
            }
        }
        return new DecodedArticles(entries, rejected);
    }

    private SupplierPriceCatalogEntry toEntry(
            PricatB40Wire.Article article,
            String documentId,
            LocalDate documentDate,
            String countryCode,
            String currency) {
        BigDecimal netPrice = parseAmount(article.netValue(), "netValue");
        BigDecimal grossPrice = parseAmount(article.grossPrice(), "grossPrice");
        LocalDate netFrom = parseVendorDate(article.netValueValidFrom(), "netValueValidFrom");
        LocalDate grossFrom = parseVendorDate(article.grossPriceValidFrom(), "grossPriceValidFrom");

        LocalDate effectiveFrom = netPrice != null && netFrom != null ? netFrom : grossFrom;
        if (effectiveFrom == null) {
            effectiveFrom = netFrom != null ? netFrom : documentDate;
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "line states no effective date and the document header carries none either");
        }
        if (countryCode == null || currency == null) {
            throw new IllegalArgumentException("document header states no "
                    + (countryCode == null ? "countryCode" : "currency") + ", so the line has no market scope");
        }

        // The canonical record rejects a line with no identity at all; surface that as a rejected
        // line rather than an exception escaping the decode.
        return new SupplierPriceCatalogEntry(
                trimToNull(article.ean()),
                trimToNull(article.supplierCode()),
                trimToNull(article.xReferenceCode()),
                parseAmount(article.suggestedPrice(), "suggestedPrice"),
                grossPrice,
                netPrice,
                parseAmount(article.tax(), "tax"),
                parseAmount(article.recyclingFee(), "recyclingFee"),
                effectiveFrom,
                countryCode,
                currency,
                documentId,
                documentDate,
                article.pos());
    }

    @Nullable
    private BigDecimal parseAmount(@Nullable String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            // Vendors state decimals with a comma in some markets; the value is otherwise plain.
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " is not a number: '" + value + "'");
        }
    }

    @Nullable
    private LocalDate parseVendorDate(@Nullable String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, VENDOR_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " is not a yyyyMMdd date: '" + value + "'");
        }
    }

    @Nullable
    private LocalDate parseHeaderDate(@Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, VENDOR_TIMESTAMP);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value, VENDOR_DATE);
            } catch (Exception alsoIgnored) {
                // A header date that will not parse is not worth failing an entire catalog over:
                // per-line validity dates carry the effective-dating contract, and the document
                // date is descriptive.
                return null;
            }
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
}
