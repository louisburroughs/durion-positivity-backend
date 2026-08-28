package com.positivity.supplier.internal.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(
        description =
                "Create/update payload for a vendor commercial account (ADR-0050 §5): one BILLING account per profile, one DELIVERY account per (profile, pos-location). Account numbers are ordinary profile data, not credentials.")
public record CommercialAccountRequest(
        @Schema(
                description =
                        "Canonical account role. BILLING is the invoicing account; DELIVERY maps a pos-location to its vendor account number.",
                example = "DELIVERY")
        @NonNull
        SupplierAccountRole role,

        @Schema(
                description =
                        "Vendor-assigned account number. Never blank. Ordinary configuration data, not a credential.",
                example = "FR-0042871")
        @NonNull
        String accountNumber,

        @Schema(description = "Vendor-assigned agency/branch code, when the vendor issues one.", example = "PAR01")
        @Nullable
        String agencyCode,

        @Schema(
                description =
                        "pos-location identifier this delivery account belongs to. Required for DELIVERY, must be absent for BILLING.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70")
        @Nullable
        UUID deliveryLocationId) {

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
