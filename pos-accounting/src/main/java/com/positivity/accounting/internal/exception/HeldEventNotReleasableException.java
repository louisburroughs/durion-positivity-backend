package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A held accounting event that a reprocess cannot release (#2620, Accounting ruling of 2026-10-17): no dedicated
 * route re-derives its outcome from the stored fact, and the posting engine cannot handle it (a Kafka row carries no
 * organization id and no posting rule set). The row is left exactly as it was, its hold reason included, and no
 * attempt row is written: nothing was attempted. 409 {@code ACCOUNTING_EVENT_HOLD_NOT_RELEASABLE} (ADR-0017), with a
 * {@code nextAction} naming what releases the hold instead.
 */
public class HeldEventNotReleasableException extends RuntimeException {

    public static final String CODE = "ACCOUNTING_EVENT_HOLD_NOT_RELEASABLE";

    private final transient String nextAction;

    public HeldEventNotReleasableException(@NonNull String message, @Nullable String nextAction) {
        super(message);
        this.nextAction = nextAction;
    }

    public @Nullable String getNextAction() {
        return nextAction;
    }
}
