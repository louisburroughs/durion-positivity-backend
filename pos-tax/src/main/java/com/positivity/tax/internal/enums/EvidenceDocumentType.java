package com.positivity.tax.internal.enums;

/**
 * The document types an evidence rule can apply to (CAP:550 S32b, AW53).
 * <p>
 * This is the closed vocabulary of {@code pos.tax.countries.<country>.evidence-rules[n].applies-to}:
 * an unknown value fails startup.
 */
public enum EvidenceDocumentType {
    /** A receipt recorded against a cash drawer (a petty expense). */
    DRAWER_RECEIPT,
    /** A vendor's bill recorded in accounts payable. */
    VENDOR_BILL
}
