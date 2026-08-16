package com.positivity.domainevents.supplier;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One line of a {@link SupplierInvoiceReceivedV1}, as the vendor stated it.
 *
 * <p>The article is named however the vendor names it. Resolving that to a catalog product is the
 * consumer's problem and may fail; an invoice whose articles cannot be matched is still an invoice
 * that has to be paid, so nothing here is withheld for being unresolvable.
 *
 * @param lineNumber           position on the document
 * @param articleEan           EAN as the vendor stated it, when given
 * @param supplierArticleCode  the vendor's own article code, when given
 * @param quantity             quantity invoiced, when given
 * @param unitPrice            unit price as stated
 * @param lineAmount           the line's net amount as stated; not derived from quantity × price,
 *                             which would silently disagree with the vendor over rounding
 * @param vendorOrderReference the vendor's order reference for this line, where it differs from the
 *                             document's
 */
public record SupplierInvoiceLine(
        int lineNumber,
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        @Nullable Integer quantity,
        @Nullable BigDecimal unitPrice,
        @Nullable BigDecimal lineAmount,
        @Nullable String vendorOrderReference) {}
