package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Create/update payload for a vendor profile (ADR-0050 §2). Profiles created through the admin
 * API are {@code ADMIN}-managed; the configuration source is never client-settable
 * (ADR-0050 §6).
 *
 * <p>Protocol-default fields are wrappers: {@code null} means "use the deployment default",
 * never zero.
 *
 * @param supplierRef unique human-readable profile alias (ADR-0050 §1), e.g.
 *     {@code "michelin-eu"}; never blank, never an identifier crossing a contract boundary
 * @param displayName admin-screen display name; never blank
 * @param enabled whether the profile's bindings resolve at all
 * @param sandbox whether the profile runs against the vendor's sandbox environment
 *     (ADR-0050 §2 sandbox overlay)
 * @param connectTimeoutMillis default connect timeout for the profile's bindings; {@code > 0}
 *     when present
 * @param readTimeoutMillis default read timeout for the profile's bindings; {@code > 0} when
 *     present
 * @param maxRetries default pre-send retry budget for the profile's bindings; {@code >= 0}
 *     when present
 * @param sandboxBaseUrlOverride base URL the profile's bindings use while {@code sandbox} is
 *     set (ADR-0050 §2 sandbox overlay); {@code null} means the bindings' own base URLs apply
 *     unchanged. Never blank when present — a blank override would silently resolve to no host
 * @param retryBackoff default retry backoff strategy for the profile's bindings; {@code null}
 *     means the deployment default
 */
@Schema(
        description =
                "Create/update payload for a vendor profile — one configured supplier connection (ADR-0050 §1/§2). The configuration source is never client-settable: profiles created here are ADMIN-managed.")
public record VendorProfileRequest(
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

    public VendorProfileRequest {
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (supplierRef.isBlank()) {
            throw new IllegalArgumentException("supplierRef must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (connectTimeoutMillis != null && connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must be > 0");
        }
        if (readTimeoutMillis != null && readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("readTimeoutMillis must be > 0");
        }
        if (maxRetries != null && maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (sandboxBaseUrlOverride != null && sandboxBaseUrlOverride.isBlank()) {
            throw new IllegalArgumentException("sandboxBaseUrlOverride must not be blank when present");
        }
    }
}
