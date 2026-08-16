package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A fleet program refused to authorize work on a workorder, published on
 * {@code supplier.events.v1} (CAP-323 #1229, ADR-0049 §3).
 *
 * <h2>Refusal is an answer, not a failure</h2>
 *
 * A denial is the vendor doing its job. It is deliberately a separate event from
 * {@link SupplierWorkorderAuthGrantedV1} rather than a status field on one, so a consumer cannot
 * subscribe to "authorization decided" and then forget to branch. The two outcomes lead to
 * completely different work in a shop, and the shape of the event should make that unavoidable.
 *
 * <p>Distinguish this from an authorization that could not be obtained — a vendor being unreachable,
 * a token that would not mint, a poll that never reached a terminal state. Those are not denials and
 * are never published as one: they stay in the supplier domain as {@code MANUAL_REVIEW}, because
 * telling a shop the fleet said no when in fact nobody asked is worse than telling it nothing.
 *
 * @param vendorProfileId       the vendor profile that made the decision
 * @param supplierRef           that profile's alias at decision time; descriptive, never a key
 * @param workorderId           the pos-workorder identity the decision concerns
 * @param vendorAuthorizationId the vendor's own identifier for the request, where it gave one
 * @param reasonCode            the vendor's own refusal code, as stated. Not translated to a Durion
 *                              vocabulary: the code is what a shop quotes back to the fleet
 * @param reasonText            the vendor's refusal text, as stated
 * @param occurredAt            when the decision was observed
 */
public record SupplierWorkorderAuthDeniedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull UUID workorderId,
        @Nullable String vendorAuthorizationId,
        @Nullable String reasonCode,
        @Nullable String reasonText,
        @NonNull Instant occurredAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.workorderauth.denied";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public SupplierWorkorderAuthDeniedV1 {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
