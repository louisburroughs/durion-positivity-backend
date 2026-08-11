package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * State of a fleet workorder authorization at the vendor (Michelin S2S). Feeds the
 * {@code supplier.workorderauth.granted} / {@code supplier.workorderauth.denied} results
 * consumed by pos-workorder (ADR-0049 §3); workorder state itself stays in pos-workorder.
 *
 * <p>Pragmatic/minimal for the CAP-317 foundation slice; grows with CAP-323 (vehicles,
 * contracts, policies, completion approval).
 *
 * @param vendorAuthorizationId vendor-native authorization identity, carried as an attribute;
 *     {@code null} until the vendor has assigned one
 * @param workorderId pos-workorder identity the authorization concerns
 * @param status vendor decision state
 * @param vendorReason vendor-supplied reason (e.g. denial detail), verbatim
 */
public record SupplierWorkorderAuthorization(
        @Nullable String vendorAuthorizationId,
        @NonNull UUID workorderId,
        @NonNull Status status,
        @Nullable String vendorReason) {

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

    public SupplierWorkorderAuthorization {
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
