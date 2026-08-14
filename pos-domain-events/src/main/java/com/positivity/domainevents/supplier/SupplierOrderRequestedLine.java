package com.positivity.domainevents.supplier;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One line of a {@link SupplierOrderRequestedV1} command (ADR-0049 §3, CAP-320 #1226).
 *
 * <p>At least one product identity is required — an EAN or the vendor's own article code — and
 * pos-supplier rejects the command rather than transmitting a line no vendor could resolve. The
 * codes travel as identity here because the ordering domain, not pos-supplier, knows which
 * product it means; pos-supplier only knows how to say it in a vendor's norm.
 *
 * @param lineNumber 1-based line number within the purchase order; becomes the wire
 *     {@code LineID} and is what the vendor echoes back on status
 * @param articleEan EAN/GTIN of the article, when known
 * @param supplierArticleCode the vendor's own article code, when known
 * @param quantity quantity to order; must be {@code >= 1}
 * @param requestedDeliveryDate the delivery date to ask the vendor for on this line; null to
 *     ask for none. Never confused with what the vendor later confirms — those are separate
 *     facts carried separately on {@link SupplierOrderStatusChangedV1}
 */
public record SupplierOrderRequestedLine(
        int lineNumber,
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        int quantity,
        @Nullable LocalDate requestedDeliveryDate) {}
