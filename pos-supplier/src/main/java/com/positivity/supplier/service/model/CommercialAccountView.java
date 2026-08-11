package com.positivity.supplier.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a commercial account on a vendor profile (ADR-0050 §5).
 *
 * @param accountId account identity
 * @param role canonical account role
 * @param accountNumber vendor account number; never blank
 * @param agencyCode identification agency code (e.g. EAN/GLN), when the vendor requires one
 * @param deliveryLocationId pos-location UUID of the receiving location; present exactly for
 *     {@link SupplierAccountRole#DELIVERY} rows
 */
public record CommercialAccountView(
        @NonNull UUID accountId,
        @NonNull SupplierAccountRole role,
        @NonNull String accountNumber,
        @Nullable String agencyCode,
        @Nullable UUID deliveryLocationId) {

    public CommercialAccountView {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        if (accountNumber.isBlank()) {
            throw new IllegalArgumentException("accountNumber must not be blank");
        }
    }
}
