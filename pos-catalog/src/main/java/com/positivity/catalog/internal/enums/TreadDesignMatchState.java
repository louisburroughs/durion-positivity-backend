package com.positivity.catalog.internal.enums;

/**
 * Where a vendor tread design stands in the enrichment review cycle (#1645).
 *
 * <p>#1352 had no such notion: a design was either pointed at by some product or it was not, which
 * conflated "the matcher is confident" with "a person decided" and left no way to record a
 * judgement at all. The states below separate the three things a reviewer actually needs to
 * distinguish — nothing plausible was found, something plausible was found but nobody has ruled on
 * it, and somebody has ruled.
 */
public enum TreadDesignMatchState {

    /** Matching found no candidate above the review floor. The ordinary outcome for a new design. */
    UNMATCHED,

    /**
     * Matching found candidates worth a person's attention and needs a person's decision — either
     * because the best score fell short of the auto tier, or because two designs claimed one
     * product at the auto tier and guessing between them would be worse than asking. This does not
     * mean the design holds no attachments: ambiguity parking (see the enrichment listener's
     * ambiguous-claim handling) can move an already-{@code MATCHED} rival design back to {@code
     * REVIEW} without clearing its other, unrelated attachments from earlier passes.
     */
    REVIEW,

    /** Attached to at least one product, by the matcher (AUTO) or by a reviewer (MANUAL). */
    MATCHED,

    /**
     * A reviewer said none of the candidates is right. Re-enters matching only when the vendor
     * changes what it published ({@code contentHash} changes) — the rejection was of what the
     * vendor said, so new words deserve a fresh look.
     */
    REJECTED,

    /** A reviewer postponed the decision, optionally until {@code deferUntil}. */
    DEFERRED
}
