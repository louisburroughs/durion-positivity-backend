package com.positivity.marketing.internal.service;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * How this module asks whether a promotion offer is actually running (Story #1148).
 *
 * <p>Stated as a port for the same reason {@link MessageChannelPort} is: the business rule —
 * a campaign may not advertise an offer that is not live — belongs here, while reaching the
 * pricing service is an integration detail that lives in {@code internal.client}.
 *
 * <p>Implementations never throw. Failing to reach pricing is a legitimate, expected outcome
 * with its own meaning, and it is reported through {@link Outcome} rather than as an
 * exception, because the caller must be able to say "we could not check" without confusing it
 * with "this offer is wrong".
 */
public interface PromotionOfferPort {

    /** The only offer status a campaign may promote. */
    String STATUS_ACTIVE = "ACTIVE";

    /** Look up one offer. Never throws: every failure mode is reported through {@link Outcome}. */
    @NonNull
    OfferLookup findOffer(@NonNull UUID promotionOfferId);

    /** Why a lookup ended the way it did. */
    enum Outcome {
        /** The offer exists; {@link OfferLookup#offer()} is populated. */
        FOUND,
        /** Pricing answered authoritatively that no such offer exists. */
        NOT_FOUND,
        /** Pricing could not be reached or refused the read; nothing is known either way. */
        UNAVAILABLE
    }

    /** Result of one offer lookup. {@code offer} is populated only when {@code outcome} is FOUND. */
    record OfferLookup(@NonNull Outcome outcome, @Nullable PromotionOffer offer) {

        public static OfferLookup found(@NonNull PromotionOffer offer) {
            return new OfferLookup(Outcome.FOUND, offer);
        }

        public static OfferLookup notFound() {
            return new OfferLookup(Outcome.NOT_FOUND, null);
        }

        public static OfferLookup unavailable() {
            return new OfferLookup(Outcome.UNAVAILABLE, null);
        }
    }

    /**
     * The parts of a promotion offer a campaign cares about.
     *
     * <p>{@code startDate}/{@code endDate} are plain dates with no zone, because pricing stores
     * them that way — an offer runs on calendar days, not instants.
     *
     * @param status one of DRAFT, ACTIVE, INACTIVE, EXPIRED
     */
    record PromotionOffer(
            @NonNull UUID promotionOfferId,
            @Nullable String promoCode,
            @Nullable String status,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate) {

        public boolean isActive() {
            return STATUS_ACTIVE.equalsIgnoreCase(status);
        }

        /**
         * Whether the offer's calendar window contains {@code today}. Checked separately from
         * {@code status} because pricing stores EXPIRED as a status that something has to set —
         * an offer whose end date has passed can still read ACTIVE until that happens.
         */
        public boolean runsOn(@NonNull LocalDate today) {
            return (startDate == null || !today.isBefore(startDate)) && (endDate == null || !today.isAfter(endDate));
        }
    }
}
