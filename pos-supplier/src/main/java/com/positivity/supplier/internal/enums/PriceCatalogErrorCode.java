package com.positivity.supplier.internal.enums;

/**
 * Stable machine-readable failure category of a FAILED PRICAT import (#1637 decision 5).
 *
 * <p>Complements the free-text {@code failureDetail} rather than replacing it: the code is what a
 * client or an alert rule switches on, the text is what the operator investigating one run reads.
 * Both are set together wherever a failed run is recorded.
 *
 * <p>The set is deliberately closed over what the importer can actually distinguish. Two failure
 * families never reach an import row and therefore have no code here: configuration defects
 * (unknown profile, unbound capability, missing billing account) are raised as
 * {@link com.positivity.supplier.internal.exception.SupplierConfigurationException} <em>before</em>
 * a manifest exists, and a persistence failure rolls the manifest back with the transaction that
 * would have recorded it. Inventing codes for them would document states no row can be in.
 */
public enum PriceCatalogErrorCode {

    /** The vendor exchange itself failed: transport error, timeout, or a non-success outcome. */
    FETCH_FAILED,

    /** The vendor answered, but the document could not be decoded as the bound protocol. */
    DECODE_FAILED
}
