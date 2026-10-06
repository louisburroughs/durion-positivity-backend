package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a vendor in pos-supplier's vendor master was created or changed (ADR-0070 Decision 2,
 * SPEC-accounting-workspace §4.9 "The fact", durion-positivity-backend#2516).
 *
 * <p>Published by pos-supplier — and only by pos-supplier (ADR-0044 R6) — on
 * {@code supplier.events.v1} with {@code eventType = "supplier.vendor.updated"}, keyed on
 * {@code vendorId}. Every committed create, update, status change and approved remit-to change
 * queues one, carrying the vendor's whole current state, so a consumer replica (pos-accounting,
 * pos-order) is a straight overwrite. The envelope's {@code aggregateVersion} is the vendor's
 * optimistic-lock version, which advances with every committed change. Consumers skip a fact only
 * when they already hold a <em>newer</em> version ({@code ReplicaVersionGuard.isStale}); an
 * <em>equal</em> version is re-applied, because a replay re-sends the current version and must be
 * able to repair a drifted replica.
 *
 * <h2>What is never here</h2>
 *
 * Bank details. Where the shop's money goes electronically is not on this record or on Kafka
 * (SPEC §4.9 "Bank details", OI-14). A remit-to change waiting for a second person's approval is
 * not here either: only an approved remit-to is published, so {@code remitTo} is always the address
 * a payment may be sent to.
 *
 * <p>Additions must be additive-only within schema version 1 (ADR-0044 §3).
 *
 * @param vendorId the vendor's identity (also the envelope aggregateId and the record key)
 * @param vendorNumber the tenant-unique, immutable reference people quote (ADR-0064), e.g.
 *     {@code V-000001} or {@code MICHELIN}
 * @param legalName the vendor's legal name
 * @param displayName the name screens show
 * @param taxRegistrations the vendor's tax registrations; empty when none are recorded
 * @param remitTo the approved remit-to address; {@code null} while the vendor has none
 *     ({@code remitToVersion = 0})
 * @param remitToVersion 0 with no remit-to, 1 for a remit-to given at creation, then +1 per
 *     approved change
 * @param defaultPaymentTerms {@code DUE_ON_RECEIPT} or {@code NET<n>} (n 1..120); {@code null}
 *     only on a vendor backfilled from a pre-existing profile and not yet completed by a person
 * @param defaultCurrency ISO 4217 code; {@code null} on the same backfilled vendors
 * @param status {@code ACTIVE} or {@code INACTIVE}; deactivation is published as {@code INACTIVE}
 * @param statusChangedAt when the status last changed; {@code null} until it first changes
 * @param statusReason the reason given for the last status change; {@code null} until then
 * @param remitToChangedAt when the current remit-to took effect (creation or approval);
 *     {@code null} with no remit-to
 * @param remitToRequestedBy principal who requested the current remit-to; for a remit-to given at
 *     creation, the creator. {@code null} with no remit-to
 * @param remitToApprovedBy principal who approved the current remit-to; {@code null} for a remit-to
 *     given at creation (no approval took place) or with no remit-to
 * @param createdBy principal who created the vendor
 * @param createdAt when the vendor was created
 * @param occurredAt when the change this fact reports was committed (or, for a replay, re-sent)
 */
public record SupplierVendorUpdatedV1(
        @NonNull UUID vendorId,
        @NonNull String vendorNumber,
        @NonNull String legalName,
        @NonNull String displayName,
        @NonNull List<TaxRegistration> taxRegistrations,
        @Nullable RemitTo remitTo,
        int remitToVersion,
        @Nullable String defaultPaymentTerms,
        @Nullable String defaultCurrency,
        @NonNull Status status,
        @Nullable Instant statusChangedAt,
        @Nullable String statusReason,
        @Nullable Instant remitToChangedAt,
        @Nullable String remitToRequestedBy,
        @Nullable String remitToApprovedBy,
        @NonNull String createdBy,
        @NonNull Instant createdAt,
        @NonNull Instant occurredAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.vendor.updated";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /** Vendor status. A vendor is never deleted, only deactivated. */
    public enum Status {
        ACTIVE,
        INACTIVE
    }

    /**
     * One tax registration of the vendor.
     *
     * @param scheme the registration scheme, e.g. {@code GST_HST}, {@code QST}, {@code EIN}
     * @param number the registration number as issued
     * @param region the issuing region where the scheme is regional; {@code null} otherwise
     */
    public record TaxRegistration(
            @NonNull String scheme,
            @NonNull String number,
            @Nullable String region) {
        public TaxRegistration {
            Objects.requireNonNull(scheme, "scheme must not be null");
            Objects.requireNonNull(number, "number must not be null");
        }
    }

    /**
     * The postal address payments are sent to. Never carries bank details (OI-14).
     *
     * @param payeeName the name a cheque is made out to
     * @param addressLine1 first address line
     * @param addressLine2 second address line, when there is one
     * @param city city
     * @param region state, province or region
     * @param postalCode postal or ZIP code
     * @param countryCode ISO 3166-1 alpha-2 country code
     * @param remittanceEmail where remittance advice is emailed, when given
     */
    public record RemitTo(
            @NonNull String payeeName,
            @NonNull String addressLine1,
            @Nullable String addressLine2,
            @NonNull String city,
            @NonNull String region,
            @NonNull String postalCode,
            @NonNull String countryCode,
            @Nullable String remittanceEmail) {
        public RemitTo {
            Objects.requireNonNull(payeeName, "payeeName must not be null");
            Objects.requireNonNull(addressLine1, "addressLine1 must not be null");
            Objects.requireNonNull(city, "city must not be null");
            Objects.requireNonNull(region, "region must not be null");
            Objects.requireNonNull(postalCode, "postalCode must not be null");
            Objects.requireNonNull(countryCode, "countryCode must not be null");
        }
    }

    public SupplierVendorUpdatedV1 {
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        Objects.requireNonNull(vendorNumber, "vendorNumber must not be null");
        Objects.requireNonNull(legalName, "legalName must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(taxRegistrations, "taxRegistrations must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (remitToVersion < 0) {
            throw new IllegalArgumentException("remitToVersion must be >= 0");
        }
        taxRegistrations = List.copyOf(taxRegistrations);
    }

    /** Whether the vendor may be named on new purchase orders, bills and payments. */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
