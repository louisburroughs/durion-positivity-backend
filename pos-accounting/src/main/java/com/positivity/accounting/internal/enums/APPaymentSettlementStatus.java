package com.positivity.accounting.internal.enums;

/**
 * Whether the gateway has settled an AP payment's charge (#2642, CAP:550). Null on {@code CASH} payments and on rows
 * created before the field existed.
 *
 * <ul>
 *   <li>{@link #IN_FLIGHT}: the gateway accepted the instruction with {@code PENDING} or {@code AUTHORIZED} (an ACH in
 *       flight), or an orphan was reconciled {@code CHARGED} without a settled answer; the settlement job re-reads it.
 *   <li>{@link #SETTLED}: the gateway answered {@code SUCCEEDED}; never re-read (a late return reaches the books through
 *       the bank statement and {@code record-return}).
 *   <li>{@link #RETURNED}: the charge failed after it moved money; the payment is {@code RETURN_PENDING} or {@code
 *       RETURNED}.
 * </ul>
 */
public enum APPaymentSettlementStatus {
    IN_FLIGHT,
    SETTLED,
    RETURNED
}
