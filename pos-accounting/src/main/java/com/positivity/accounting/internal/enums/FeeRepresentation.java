package com.positivity.accounting.internal.enums;

/**
 * Persisted mirror of {@code SettlementProviderConfigV1.FeeRepresentation} (decision D-12): how the
 * provider reports fees across a settlement. Regardless of mode, accounting posts one {@code Dr
 * Processor Fees = header.feeAmount}; the mode only tells the consumer whether line-level fee/net
 * fields are populated.
 */
public enum FeeRepresentation {
    SEPARATE_LINES,
    NETTED_PER_LINE,
    HEADER_ONLY
}
