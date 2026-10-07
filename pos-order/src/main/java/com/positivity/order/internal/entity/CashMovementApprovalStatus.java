package com.positivity.order.internal.entity;

/** A manager approval token's life: issued once, then used by one movement or expired (#2512, AW31). */
public enum CashMovementApprovalStatus {
    ISSUED,
    USED,
    EXPIRED
}
