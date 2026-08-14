package com.positivity.inventory.internal.enums;

/**
 * Whose clock the age of a supplier stock hint is measured against (CAP-322, #1312).
 *
 * <p>{@code snapshotAsOf} on {@code supplier.stockreport.updated} is nullable — B2.1 permits a
 * vendor to state no time — so a hint's age is sometimes only knowable from when we asked. Which
 * one is in play is recorded explicitly rather than substituted silently: a caller must never be
 * shown our fetch clock believing it to be the vendor's figure.
 *
 * <ul>
 *   <li>{@code VENDOR} — the vendor stated the instant its snapshot describes. Freshness is judged
 *       on this.</li>
 *   <li>{@code FETCH} — the vendor stated no instant, so the age shown is the age of our fetch,
 *       which bounds the hint's age from below but does not establish it.</li>
 * </ul>
 */
public enum SupplierHintAsOfSource {
    VENDOR,
    FETCH
}
