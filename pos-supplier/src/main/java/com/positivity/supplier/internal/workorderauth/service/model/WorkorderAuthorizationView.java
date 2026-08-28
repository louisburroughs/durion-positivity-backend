package com.positivity.supplier.internal.workorderauth.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Vendor-side state of a fleet workorder authorization (Michelin S2S). Placeholder-level for
 * the CAP-317 foundation slice; grows with CAP-323 (vehicles, contracts, policies, completion
 * approval).
 *
 * @param workorderId pos-workorder identity the authorization concerns
 * @param status vendor decision state
 * @param vendorAuthorizationId vendor-native authorization identity, carried as an attribute
 *     (ADR-0013/0027); {@code null} until the vendor has assigned one
 * @param vendorReason vendor-supplied reason (e.g. denial detail), verbatim
 */
public record WorkorderAuthorizationView(
        @NonNull UUID workorderId,
        @NonNull Status status,
        @Nullable String vendorAuthorizationId,
        @Nullable String vendorReason) {

    /** Vendor decision states. */
    public enum Status {
        /** Authorization requested; the vendor has not decided yet. */
        PENDING,
        /** The vendor granted the authorization. */
        GRANTED,
        /** The vendor denied the authorization. */
        DENIED,
        /** The vendor has no such authorization. */
        NOT_FOUND
    }

    public WorkorderAuthorizationView {
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
