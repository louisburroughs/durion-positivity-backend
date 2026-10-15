package com.positivity.order.internal.service.model;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One changed drawer-policy setting (CAP:550 S16, #2512): old and new value, actor and justification, and the actor's
 * display name resolved at read time (#2608), null when it can't be resolved.
 */
public record SessionPolicyChangeView(
        @NonNull String setting,
        @Nullable String oldValue,
        @Nullable String newValue,
        @NonNull String actor,
        @NonNull String justification,
        @NonNull Instant changedAt,
        @Nullable String actorName) {

    /** Never prints the actor's display name (ADR-0072). */
    @Override
    public String toString() {
        return "SessionPolicyChangeView[setting=" + setting + ", oldValue=" + oldValue + ", newValue=" + newValue
                + ", actor=" + actor + ", changedAt=" + changedAt + "]";
    }
}
