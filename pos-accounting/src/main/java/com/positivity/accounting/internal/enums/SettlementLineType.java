package com.positivity.accounting.internal.enums;

/**
 * Persisted mirror of {@code SettlementReportedV1.LineType} (frozen contract, decision D-10). Kept
 * as its own enum so the persistence layer does not couple to the event module's type; consumers
 * translate the contract enum to this on ingest. Any contract line type the matcher cannot map is
 * still persisted here and left {@link SettlementLineMatchStatus#UNMATCHED} (never mis-posted).
 */
public enum SettlementLineType {
    CHARGE,
    REFUND,
    CHARGEBACK,
    FEE,
    ADJUSTMENT,
    RESERVE_HOLD,
    RESERVE_RELEASE,
    PAYOUT_CORRECTION
}
