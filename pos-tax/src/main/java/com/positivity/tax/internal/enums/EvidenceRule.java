package com.positivity.tax.internal.enums;

/**
 * The evidence an evidence rule can require (CAP:550 S32b, AW53).
 * <p>
 * This is the closed vocabulary of {@code pos.tax.countries.<country>.evidence-rules[n].rule}: an
 * unknown value fails startup. It names a kind of evidence, never a country, regime or tax type;
 * which country requires it, from which amount and for which documents is configuration.
 */
public enum EvidenceRule {
    /** The supplier's registration number, matching the country's supplier regime shape. */
    SUPPLIER_REGISTRATION_NUMBER
}
