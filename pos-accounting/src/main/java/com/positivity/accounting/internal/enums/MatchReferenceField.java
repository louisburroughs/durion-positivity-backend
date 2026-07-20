package com.positivity.accounting.internal.enums;

/**
 * Persisted mirror of {@code SettlementProviderConfigV1.MatchReferenceField} (decision D-11): which
 * correlation reference a settlement line is matched on. Matching always compares line gross, never
 * net (decision D-12).
 */
public enum MatchReferenceField {
    PAYMENT_REFERENCE,
    PROVIDER_LINE_REF
}
