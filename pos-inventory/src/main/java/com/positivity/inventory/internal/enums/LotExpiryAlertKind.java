package com.positivity.inventory.internal.enums;

/**
 * Kind of lot-expiry transition the daily {@code LotExpiryScheduler} raises (odoo-parity E3,
 * issue #1047; spec §6 E3), carried as the {@code alertKind} of {@code LotExpiryAlertV1}.
 */
public enum LotExpiryAlertKind {
    /** The lot reached its alert window ({@code alertDate <= today <= expirationDate}). */
    EXPIRING,
    /** The lot's expiration date has passed ({@code expirationDate < today}). */
    EXPIRED
}
