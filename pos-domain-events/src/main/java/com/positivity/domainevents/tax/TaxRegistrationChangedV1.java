package com.positivity.domainevents.tax;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a tenant's indirect-tax registration was created or changed in pos-tax (CAP:550 S32c; ADR-0071 §7,
 * ADR-0044 §1 as amended 2026-10-08, AW58).
 *
 * <p>Published by pos-tax through its transactional outbox on {@code tax.events.v1} with {@code eventType =
 * "tax.registration.changed"}, one fact per committed change, keyed by the registration id. pos-tax also
 * publishes a per-tenant reconciliation manifest on {@code tax.manifest.v1} and re-sends a window on a
 * consumer's {@code tax.outbox.replay-requested} command (ADR-0044 §4). pos-accounting and pos-order keep
 * {@code ext_tax_registration} copies, idempotent per {@code registrationId} and {@code version}, read as of a
 * business date (AW49).
 *
 * <p>The registry is country-agnostic: {@code countryCode}, {@code regime} and {@code jurisdictionCode} come from
 * pos-tax's configured country profiles; no country or regime is named in code.
 *
 * <p>{@code registrationNumber} is INTERNAL under ADR-0072 Decision 1: pos-tax stores and publishes a number only
 * after it matches its regime's closed, configured shape (conditions a-c, Security confirmation and decision on
 * louisburroughs/durion#571). It is still never logged: {@link #toString()} masks it.
 *
 * @param registrationId     the registration (also the envelope aggregateId and the Kafka record key)
 * @param tenantId           the registering tenant
 * @param countryCode        ISO 3166-1 alpha-2 country of the regime
 * @param regime             the regime code the country profile declares
 * @param registrationNumber the shape-checked number in its normalised form (INTERNAL, never logged)
 * @param jurisdictionCode   the regime's single region when it lists exactly one, else the country
 * @param effectiveFrom      inclusive first day the registration is in effect
 * @param effectiveTo        inclusive last day it is in effect; {@code null} while open-ended
 * @param status             {@code SCHEDULED}, {@code ACTIVE} or {@code ENDED}, derived on {@code changedAt}'s UTC
 *                           date; a consumer reading as of a date uses the effective dates, not this
 * @param version            the registration's version after the change; strictly advancing (also the
 *                           envelope aggregateVersion)
 * @param changedAt          when pos-tax committed the change
 */
public record TaxRegistrationChangedV1(
        @NonNull UUID registrationId,
        @NonNull UUID tenantId,
        @NonNull String countryCode,
        @NonNull String regime,
        @NonNull String registrationNumber,
        @NonNull String jurisdictionCode,
        @NonNull LocalDate effectiveFrom,
        @Nullable LocalDate effectiveTo,
        @NonNull String status,
        long version,
        @NonNull Instant changedAt) {

    public static final String EVENT_TYPE = "tax.registration.changed";
    public static final int SCHEMA_VERSION = 1;

    public TaxRegistrationChangedV1 {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        requireText(countryCode, "countryCode");
        requireText(regime, "regime");
        requireText(registrationNumber, "registrationNumber");
        requireText(jurisdictionCode, "jurisdictionCode");
        requireText(status, "status");
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("effectiveFrom must not be null");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("changedAt must not be null");
        }
    }

    private static void requireText(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** Every component but the number, which is never logged (ADR-0072; CAP:550 S32c). */
    @Override
    public @NonNull String toString() {
        return "TaxRegistrationChangedV1[registrationId=" + registrationId
                + ", tenantId=" + tenantId
                + ", countryCode=" + countryCode
                + ", regime=" + regime
                + ", registrationNumber=****"
                + ", jurisdictionCode=" + jurisdictionCode
                + ", effectiveFrom=" + effectiveFrom
                + ", effectiveTo=" + effectiveTo
                + ", status=" + status
                + ", version=" + version
                + ", changedAt=" + changedAt
                + "]";
    }
}
