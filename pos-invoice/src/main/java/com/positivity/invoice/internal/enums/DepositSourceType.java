package com.positivity.invoice.internal.enums;

/**
 * The kind of source document a deposit credit was taken against (odoo-parity story E4, issue
 * #1085, spec R7.4). Typed provenance is why deposits are a dedicated artifact rather than an
 * {@code InvoiceAdjustment} (gate V2): the credit must carry which estimate/workorder/order it came
 * from so it applies to the right settlement invoice.
 */
public enum DepositSourceType {
    ESTIMATE,
    WORKORDER,
    ORDER
}
