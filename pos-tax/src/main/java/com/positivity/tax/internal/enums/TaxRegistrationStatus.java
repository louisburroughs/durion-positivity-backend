package com.positivity.tax.internal.enums;

import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A tax registration's state on a date (CAP:550 S32c), derived from its effective dates and never stored: a
 * registration is never deleted, only ended by its inclusive {@code effectiveTo}.
 */
public enum TaxRegistrationStatus {
    /** Starts after the date. */
    SCHEDULED,
    /** In effect on the date. */
    ACTIVE,
    /** Ended before the date. */
    ENDED;

    /**
     * The state of a registration with these dates on {@code date}.
     *
     * @param effectiveFrom inclusive first day
     * @param effectiveTo   inclusive last day; {@code null} while open-ended
     * @param date          the date
     * @return the state
     */
    public static @NonNull TaxRegistrationStatus on(
            @NonNull LocalDate effectiveFrom, @Nullable LocalDate effectiveTo, @NonNull LocalDate date) {
        if (date.isBefore(effectiveFrom)) {
            return SCHEDULED;
        }
        if (effectiveTo != null && date.isAfter(effectiveTo)) {
            return ENDED;
        }
        return ACTIVE;
    }
}
