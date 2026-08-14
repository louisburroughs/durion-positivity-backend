package com.positivity.inventory.internal.enums;

/**
 * Whether a supplier stock hint has been tied to a catalog product (CAP-322, #1312).
 *
 * <p>Resolution is deliberately not done in the consumer: a chunk carries up to several hundred
 * lines and pos-inventory holds no product-code data of its own, so resolving inline would put
 * hundreds of synchronous catalog calls inside the apply transaction. It runs as a separate sweep
 * against pos-catalog's exact-match EAN lookup, and an unresolved hint is retained and visible
 * throughout — never dropped, and never guessed at.
 *
 * <ul>
 *   <li>{@code PENDING} — awaiting a resolution attempt. Set on insert, and set again whenever a
 *       newer snapshot updates a row that is not already resolved, so retries follow the vendor's
 *       own schedule instead of needing a timer of their own.</li>
 *   <li>{@code RESOLVED} — matched to exactly one catalog product by exact EAN.</li>
 *   <li>{@code UNRESOLVED} — attempted and matched nothing, or matched ambiguously. Catalog may
 *       carry the product later, so the next snapshot returns the row to {@code PENDING}.</li>
 *   <li>{@code NOT_RESOLVABLE} — the vendor stated no EAN, so there is nothing deterministic to
 *       match on. Vendor and buyer article codes are deliberately not matched here: a stock report
 *       is an availability hint, and PRICAT's matching rules may be wrong for it.</li>
 * </ul>
 */
public enum SupplierHintResolutionStatus {
    PENDING,
    RESOLVED,
    UNRESOLVED,
    NOT_RESOLVABLE
}
