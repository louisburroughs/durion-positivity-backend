package com.positivity.domainevents.supplier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A fleet program authorized work on a workorder, published on {@code supplier.events.v1}
 * (CAP-323 #1229, ADR-0049 §3).
 *
 * <h2>An input to a workflow, not a state change</h2>
 *
 * This says what the vendor decided. It does not say what the workorder should now do about it —
 * that mapping belongs to pos-workorder, which stays authoritative for workorder state (ADR-0049
 * §2). Publishing a decision and publishing a transition are different things, and conflating them
 * would put a second state machine for workorders inside the supplier domain.
 *
 * <h2>Identity, because a decision can arrive twice</h2>
 *
 * An asynchronous authorization is resolved by polling, and a poll that times out is retried, so the
 * same terminal decision can be observed more than once. A consumer must key on
 * {@code (vendorProfileId, workorderId)} rather than on the event: this is a decision about a
 * workorder, and a workorder has one live authorization at a time.
 *
 * @param vendorProfileId       the vendor profile that made the decision
 * @param supplierRef           that profile's alias at decision time; descriptive, never a key
 * @param workorderId           the pos-workorder identity the decision concerns
 * @param vendorAuthorizationId the vendor's own identifier for the authorization, where it gave one.
 *                              Needed to approve completion later, so its absence is worth noticing
 * @param contractReference     the fleet contract the authorization was granted under, as stated
 * @param authorizedAmount      the amount authorized, as stated by the vendor. Absent when the
 *                              vendor authorized the work without stating a ceiling — which is not
 *                              the same as authorizing zero
 * @param currency              ISO 4217 code for {@code authorizedAmount}; absent together with it
 * @param occurredAt            when the decision was observed
 */
public record SupplierWorkorderAuthGrantedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull UUID workorderId,
        @Nullable String vendorAuthorizationId,
        @Nullable String contractReference,
        @Nullable BigDecimal authorizedAmount,
        @Nullable String currency,
        @NonNull Instant occurredAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.workorderauth.granted";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public SupplierWorkorderAuthGrantedV1 {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (authorizedAmount != null && currency == null) {
            // An amount without a currency is not a sum of money, and a fleet ceiling that a
            // consumer has to guess the currency of is worse than no ceiling at all.
            throw new IllegalArgumentException("currency is required when authorizedAmount is present");
        }
    }
}
