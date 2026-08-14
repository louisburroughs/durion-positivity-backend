package com.positivity.supplier.internal.adapter.ediwheelb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Literal shape of an EDIWheel Stock Report B2.1 document
 * ({@code durion/docs/ediwheel/EDIWHEEL STOCK REPORT PROD_0 (1).yaml}).
 *
 * <p>Package-private (ADR-0051 §2): these are vendor types and only the codec in this package may
 * see them. Quantities arrive as strings, like every other numeric in the EDIWheel norms, and are
 * parsed at this boundary so a malformed vendor value never becomes a malformed canonical one.
 */
final class StockReportB21Wire {

    private StockReportB21Wire() {}

    /** Root document: envelope header, totals, buyer party, and the reported lines. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StockReport(
            EnvelopeHeader envelopeHeader, String totalLineItemsNumber, String buyerParty, List<Line> lineLevel) {}

    /**
     * Document-level header. {@code errorCode} is {@code "0"} on a good document; {@code issueDate}
     * and {@code issueTime} are the vendor's own statement of when the snapshot was taken, which is
     * deliberately not the same fact as when we fetched it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnvelopeHeader(String issueDate, String issueTime, String documentId, String variant, String errorCode) {}

    /** One reported line. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Line(String lineId, Article article) {}

    /** One article's identity, description and availability. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Article(
            ArticleIdentification articleIdentification,
            ArticleDescription articleDescription,
            AvailableQuantity availableQuantity) {}

    /** Vendor article identity. {@code eanuccarticleID} is the EAN/GTIN. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArticleIdentification(String buyersArticleId, String manufacturersArticleId, String eanuccarticleID) {}

    /** Vendor article description text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArticleDescription(String articleDescriptionText) {}

    /**
     * Reported quantity. Absent or blank means the vendor stated no quantity for the line, which is
     * not the same as reporting zero.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvailableQuantity(String quantityValue) {}
}
