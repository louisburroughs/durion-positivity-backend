package com.positivity.people.internal.enums;

import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Lifecycle of a {@code person_credential} row (CAP-328, spec D7). {@link #ACTIVE} and {@link
 * #EXPIRED} are derived from the dates and never trusted from a feed; {@link #REVOKED} and {@link
 * #SUPERSEDED} are the two states a date cannot produce and are set deliberately.
 */
public enum CredentialStatus {
    ACTIVE,
    EXPIRED,
    REVOKED,
    SUPERSEDED;

    /** What the dates say on {@code onDate}: ACTIVE, or EXPIRED once {@code expiresOn} has passed. */
    public static @NonNull CredentialStatus derive(
            @NonNull LocalDate issuedOn, @Nullable LocalDate expiresOn, @NonNull LocalDate onDate) {
        if (expiresOn != null && expiresOn.isBefore(onDate)) {
            return EXPIRED;
        }
        return ACTIVE;
    }

    /** Whether a row in this state can still count as a held qualification (dates permitting). */
    public boolean isHoldable() {
        return this == ACTIVE || this == EXPIRED;
    }
}
