package com.positivity.accounting.internal.enums;

/**
 * Match state of a settlement line (story F1c, issue #963).
 *
 * <p>{@code MATCHED} to a platform {@code ReceivablePayment}/{@code APPayment} on gross within the
 * provider's tolerance (decision D-11/D-12); {@code UNMATCHED} awaiting manual review; {@code
 * WRITTEN_OFF} closed by a reversible adjustment JE below the write-off threshold (decision D-14).
 */
public enum SettlementLineMatchStatus {
    MATCHED,
    UNMATCHED,
    WRITTEN_OFF
}
