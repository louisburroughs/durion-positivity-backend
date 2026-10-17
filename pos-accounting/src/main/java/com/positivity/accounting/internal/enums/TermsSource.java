package com.positivity.accounting.internal.enums;

/** Where the payment terms behind an estimated vendor-bill due date came from (AW11 amendment, 2026-10-07). */
public enum TermsSource {
    /** The purchase order's {@code paymentTermsId}, from pos-accounting's copy of the order. */
    PURCHASE_ORDER,
    /** The vendor's {@code defaultPaymentTerms}, from pos-accounting's vendor copy. */
    VENDOR,
    /** The tenant's {@code AP_DEFAULT_TERMS} setting. */
    TENANT_DEFAULT
}
