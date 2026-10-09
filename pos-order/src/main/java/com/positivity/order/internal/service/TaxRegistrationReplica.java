package com.positivity.order.internal.service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * pos-order's as-of read of the tenant's tax registrations (CAP:550 S32c; AW49), from its copy of pos-tax's
 * {@code tax.registration.changed}. pos-order never calls pos-tax for registrations.
 *
 * <p>An empty answer means the copy holds no registration in effect on that date. The copy follows pos-tax by event,
 * so a caller that must act on absence (S32d's recovery flags) retries or holds rather than reading "not
 * registered" from a copy that may not have caught up.
 */
public interface TaxRegistrationReplica {

    /**
     * The registration of one country's regime in effect on {@code businessDate} (both ends inclusive).
     *
     * @param countryCode  ISO 3166-1 alpha-2 country
     * @param regime       the regime code
     * @param businessDate the business date
     * @return the registration, or empty when the copy holds none in effect that day
     */
    @NonNull
    Optional<Registration> inEffectOn(
            @NonNull String countryCode, @NonNull String regime, @NonNull LocalDate businessDate);

    /**
     * A registration as the drawer reads it: no number, which the copy does not keep.
     *
     * @param registrationId   the pos-tax registration id
     * @param countryCode      the country
     * @param regime           the regime
     * @param jurisdictionCode the regime's single region, or the country
     * @param effectiveFrom    inclusive first day
     * @param effectiveTo      inclusive last day; {@code null} while open-ended
     */
    record Registration(
            @NonNull UUID registrationId,
            @NonNull String countryCode,
            @NonNull String regime,
            @NonNull String jurisdictionCode,
            @NonNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {}
}
