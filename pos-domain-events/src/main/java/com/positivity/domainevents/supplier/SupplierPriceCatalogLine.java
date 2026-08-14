package com.positivity.domainevents.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One matched vendor price line inside a {@link SupplierPriceCatalogUpdatedV1} chunk (ADR-0053 §7).
 *
 * <p>Only lines that resolved to a catalog product are published: an unmatched line has no product
 * to apply to, so it stays in pos-supplier's quarantine until a catalog fix makes it matchable
 * (ADR-0053 §5). Consumers therefore never have to implement their own matching.
 *
 * <p>Prices are nullable on purpose — null means "the vendor did not state this value", which is
 * different from zero. A consumer that coerces null to zero would turn an unstated net price into
 * a free product.
 *
 * @param productId           catalog product the line matched, resolved deterministically by the producer
 * @param matchMethod         which identifier produced the match: {@code EAN} or {@code UPC_XREFERENCE}
 * @param articleEan          EAN the vendor stated, when present
 * @param supplierArticleCode vendor's own article code — an alias for display, never an identifier
 * @param xReferenceCode      UPC/EAN cross-reference the vendor stated, when present
 * @param suggestedRetailPrice vendor-suggested retail price; reference-only, never a sell price (ADR-0053 §4)
 * @param grossPrice          gross (list) purchase price, verbatim
 * @param netPrice            net purchase price, verbatim
 * @param taxRate             tax rate the vendor stated for the line, when present
 * @param recyclingFee        recycling fee amount the vendor stated, when present
 * @param effectiveFrom       inclusive first day the values apply; a vendor-local date (ADR-0053 §2)
 * @param positionNumber      1-based line position in the vendor document, for citing a line back to the vendor
 */
public record SupplierPriceCatalogLine(
        @NonNull UUID productId,
        @NonNull String matchMethod,
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        @Nullable String xReferenceCode,
        @Nullable BigDecimal suggestedRetailPrice,
        @Nullable BigDecimal grossPrice,
        @Nullable BigDecimal netPrice,
        @Nullable BigDecimal taxRate,
        @Nullable BigDecimal recyclingFee,
        @NonNull LocalDate effectiveFrom,
        @Nullable Integer positionNumber) {}
