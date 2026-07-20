package com.positivity.tax.common.enums;

/**
 * Reason a line item (or an entire transaction) is claimed to be exempt from tax.
 * <p>
 * Exemptions are modeled as "reportable zero-tax" (Odoo parity, story T3): an
 * exempt line still emits zero-rate jurisdiction rows so tax-liability reports can
 * show exempt sales per jurisdiction. The reason is echoed onto the exempt rows and,
 * for a denied exemption (claimed but unbacked by a valid certificate), onto the
 * taxed line together with the {@code exemptionDenied} flag (decision D-T2).
 */
public enum ExemptionReasonCode {
    /** Purchase for resale (reseller / wholesale). */
    RESALE,
    /** Government or public-authority purchaser. */
    GOVERNMENT,
    /** Registered non-profit / charitable organization. */
    NONPROFIT,
    /** Agricultural / farming use exemption. */
    AGRICULTURAL,
    /** Any other documented exemption reason. */
    OTHER
}
