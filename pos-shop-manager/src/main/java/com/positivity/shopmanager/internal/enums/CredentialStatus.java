package com.positivity.shopmanager.internal.enums;

import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Lifecycle of a credential as this module reads it off the {@code ext_person_credential} replica
 * (CAP-328 D7). The People domain owns the aggregate; this enum exists so expiry is judged here,
 * against the date being asked about, rather than trusted from the feed.
 */
public enum CredentialStatus {
    ACTIVE,
    EXPIRED,
    REVOKED,
    SUPERSEDED;

    /**
     * The status a credential has on {@code onDate}. {@code REVOKED} and {@code SUPERSEDED} are the
     * owner's decisions and stand as received; anything else is {@code EXPIRED} once {@code
     * expiresOn} has passed and {@code ACTIVE} otherwise. A null {@code expiresOn} never expires.
     *
     * @param feedStatus the status as received on the feed; an unknown value reads as ACTIVE-until-expired
     */
    public static @NonNull CredentialStatus effective(
            @Nullable String feedStatus, @Nullable LocalDate expiresOn, @NonNull LocalDate onDate) {
        if (REVOKED.name().equals(feedStatus)) {
            return REVOKED;
        }
        if (SUPERSEDED.name().equals(feedStatus)) {
            return SUPERSEDED;
        }
        return expiresOn != null && expiresOn.isBefore(onDate) ? EXPIRED : ACTIVE;
    }

    /** Whether a credential in this status counts as held. */
    public boolean isHeld() {
        return this == ACTIVE;
    }
}
