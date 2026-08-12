package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * @param sandboxBaseUrlOverride base URL used while {@code sandbox} is set (ADR-0050 §2);
 *     {@code null} means the bindings' own base URLs apply unchanged
 * @param retryBackoff default retry backoff strategy; {@code null} means deployment default
 */
@Schema(
        description =
                "Read model of a vendor profile. Child collections (auth configs, commercial accounts, endpoint bindings) are read through their own list operations.")
public record VendorProfileView(
        @Schema(
                description = "Platform identity of the profile (UUIDv7, ADR-0050 §1).",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID vendorProfileId,

        @Schema(
                description =
                        "Unique human-readable profile alias (ADR-0050 §1). Never blank. Identifies the configuration, and is never used as an identifier across a contract boundary.",
                example = "michelin-eu")
        @NonNull
        String supplierRef,

        @Schema(description = "Admin-screen display name. Never blank.", example = "Michelin Europe") @NonNull
        String displayName,

        @Schema(
                description =
                        "Whether the profile's bindings resolve at all. A disabled profile resolves every capability to a typed not-configured outcome.",
                example = "true")
        boolean enabled,

        @Schema(
                description =
                        "Whether the profile runs against the vendor's sandbox environment (ADR-0050 §2 sandbox overlay).",
                example = "false")
        boolean sandbox,

        @Schema(
                description =
                        "Authoritative configuration source (ADR-0050 §6). Every mutation is rejected with 409 while this is YAML.",
                example = "ADMIN")
        @NonNull
        ProfileSourceOfTruth sourceOfTruth,

        @Schema(
                description =
                        "Default connect timeout for the profile's bindings, in milliseconds. Must be > 0 when present; omit to use the deployment default.",
                example = "5000")
        @Nullable
        Integer connectTimeoutMillis,

        @Schema(
                description =
                        "Default read timeout for the profile's bindings, in milliseconds. Must be > 0 when present; omit to use the deployment default.",
                example = "30000")
        @Nullable
        Integer readTimeoutMillis,

        @Schema(
                description =
                        "Default pre-send retry budget for the profile's bindings. Must be >= 0 when present; omit to use the deployment default. Only pre-send failures are retried.",
                example = "2")
        @Nullable
        Integer maxRetries,

        @Schema(
                description =
                        "Base URL the profile's bindings use while sandbox is set (ADR-0050 §2). Omit to leave the bindings' own base URLs unchanged; never blank when present.",
                example = "https://sandbox.ediwheel.example/api")
        @Nullable
        String sandboxBaseUrlOverride,

        @Schema(
                description = "Default retry backoff strategy. Omit to use the deployment default.",
                example = "EXPONENTIAL")
        @Nullable
        RetryBackoff retryBackoff) {

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
