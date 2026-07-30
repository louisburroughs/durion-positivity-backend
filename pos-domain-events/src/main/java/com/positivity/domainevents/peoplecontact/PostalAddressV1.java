package com.positivity.domainevents.peoplecontact;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Structured, country-agnostic postal address owned by pos-people-contact (FI-4, #1135).
 *
 * <p>The shape is deliberately international: {@code region} is a free-text administrative
 * area (state, province, prefecture, county), {@code postalCode} is optional because several
 * countries do not use one, and no country-specific format is enforced beyond
 * {@code countryCode} being an ISO 3166-1 alpha-2 code. Rendering order is a presentation
 * concern of the consumer, keyed by {@code countryCode}.
 */
public record PostalAddressV1(
        @NonNull String line1,
        @Nullable String line2,
        @Nullable String city,
        @Nullable String region,
        @Nullable String postalCode,
        @NonNull String countryCode) {}
