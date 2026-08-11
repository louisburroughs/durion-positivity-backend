package com.positivity.supplier.service.model;

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
 */
public record VendorProfileRequest(
        @NonNull String supplierRef,
        @NonNull String displayName,
        boolean enabled,
        boolean sandbox,
        @Nullable Integer connectTimeoutMillis,
        @Nullable Integer readTimeoutMillis,
        @Nullable Integer maxRetries) {

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
    }
}
