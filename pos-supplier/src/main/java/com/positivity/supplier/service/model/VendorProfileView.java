package com.positivity.supplier.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a vendor profile (ADR-0050 §1/§2). Child collections (auth configs, commercial
 * accounts, endpoint bindings) are read through their own list operations on
 * {@code SupplierProfileAdminService}.
 *
 * @param vendorProfileId platform identity of the profile (UUIDv7, ADR-0050 §1)
 * @param supplierRef unique human-readable configuration alias; never blank
 * @param displayName admin-screen display name; never blank
 * @param enabled whether the profile's bindings resolve at all
 * @param sandbox whether the profile runs against the vendor's sandbox environment
 * @param sourceOfTruth authoritative configuration source (ADR-0050 §6); mutations are
 *     rejected when {@link ProfileSourceOfTruth#YAML}
 * @param connectTimeoutMillis default connect timeout; {@code null} means deployment default
 * @param readTimeoutMillis default read timeout; {@code null} means deployment default
 * @param maxRetries default pre-send retry budget; {@code null} means deployment default
 */
public record VendorProfileView(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String displayName,
        boolean enabled,
        boolean sandbox,
        @NonNull ProfileSourceOfTruth sourceOfTruth,
        @Nullable Integer connectTimeoutMillis,
        @Nullable Integer readTimeoutMillis,
        @Nullable Integer maxRetries) {

    public VendorProfileView {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(sourceOfTruth, "sourceOfTruth must not be null");
        if (supplierRef.isBlank()) {
            throw new IllegalArgumentException("supplierRef must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
