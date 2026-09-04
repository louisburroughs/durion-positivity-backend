package com.positivity.supplier.internal.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Read model of a vendor commercial account.")
public record CommercialAccountView(
        @Schema(
                description = "Identity of the commercial account (UUIDv7).",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62")
        @NonNull
        UUID accountId,

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

    // Left as IllegalArgumentException (#1694): this is a response view built server-side from a
    // persisted entity by the admin service, never from client input (the create/update payload
    // is CommercialAccountRequest, validated separately). A violation here is this module's own
    // defect, so it belongs on the platform 500 fallback, not a client 4xx.
    public CommercialAccountView {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        if (accountNumber.isBlank()) {
            throw new IllegalArgumentException("accountNumber must not be blank");
        }
    }
}
