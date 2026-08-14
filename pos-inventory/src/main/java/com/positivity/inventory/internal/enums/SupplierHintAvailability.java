package com.positivity.inventory.internal.enums;

/**
 * How a supplier stock hint reads to a caller (CAP-322, #1312).
 *
 * <p>These three values plus the absence of a row carry the whole distinction the producer
 * preserves. None of them may be collapsed into the others, and in particular none of them is
 * "zero" unless the vendor actually said zero.
 *
 * <ul>
 *   <li>{@code REPORTED} — the vendor stated a quantity, which the view carries. A stated
 *       {@code 0} lands here: an explicit "we have none" is information, not an absence.</li>
 *   <li>{@code QUANTITY_NOT_STATED} — the vendor listed the article and stated no quantity. The
 *       view's quantity is null.</li>
 *   <li>{@code STALE_UNKNOWN} — the hint is older than the staleness ceiling for its vendor, so its
 *       quantity is suppressed. An old hint reads as unknown, never as zero.</li>
 * </ul>
 *
 * <p>The fourth case — the vendor never mentioned the article — is the absence of a row, which is
 * why a snapshot that omits a previously reported article leaves the previous hint standing with
 * its own {@code asOf} rather than zeroing it.
 */
public enum SupplierHintAvailability {
    REPORTED,
    QUANTITY_NOT_STATED,
    STALE_UNKNOWN
}
