package com.positivity.supplier.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Create/update payload for a commercial account on a vendor profile (ADR-0050 §4/§5).
 * Account numbers are ordinary profile data, distinct from credentials: one credential set
 * authenticates the connection while account numbers identify the commercial parties inside
 * each message.
 *
 * @param role canonical account role; {@link SupplierAccountRole#DELIVERY} rows map a
 *     pos-location to its vendor account number
 * @param accountNumber vendor account number; never blank
 * @param agencyCode identification agency code (e.g. EAN/GLN), when the vendor requires one
 * @param deliveryLocationId pos-location UUID of the receiving location; required for
 *     {@link SupplierAccountRole#DELIVERY}, must be {@code null} for
 *     {@link SupplierAccountRole#BILLING}
 */
public record CommercialAccountRequest(
        @NonNull SupplierAccountRole role,
        @NonNull String accountNumber,
        @Nullable String agencyCode,
        @Nullable UUID deliveryLocationId) {

    public CommercialAccountRequest {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        if (accountNumber.isBlank()) {
            throw new IllegalArgumentException("accountNumber must not be blank");
        }
        if (role == SupplierAccountRole.DELIVERY && deliveryLocationId == null) {
            throw new IllegalArgumentException("deliveryLocationId is required for DELIVERY accounts");
        }
        if (role == SupplierAccountRole.BILLING && deliveryLocationId != null) {
            throw new IllegalArgumentException("deliveryLocationId must be null for BILLING accounts");
        }
    }
}
