package com.positivity.supplier.service.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Create/update payload for a capability endpoint binding (ADR-0050 §3): one capability mapped
 * to {@code (protocolFamily, version, baseUrl, path, authConfigName, optional schedule,
 * enabled)}. An absent binding means the capability is disabled for the profile, surfaced as a
 * typed {@code CAPABILITY_NOT_CONFIGURED} status, never an error leak.
 *
 * <p>{@code capability}, {@code protocolFamily}, and {@code version} are canonical string keys
 * mirroring the deployment YAML: capability and protocol family must name a canonical value
 * (e.g. {@code STOCK_INQUIRY}, {@code EDIWHEEL_A25}) and are validated by the implementation
 * (bad input maps to 400 per ADR-0017); the version is a norm-version key (e.g. {@code A2_5},
 * {@code C1_1}) which is <em>data, not an enum</em> — vendors add norm versions without a code
 * change (ADR-0051 §3).
 *
 * @param capability canonical capability key; never blank
 * @param protocolFamily canonical protocol family key; never blank
 * @param version norm-version key within the family; never blank
 * @param baseUrl endpoint base URL; never blank
 * @param path endpoint path relative to {@code baseUrl}; never blank
 * @param authConfigName name of the profile auth config authenticating this binding; never
 *     blank
 * @param schedule cron expression for batch capabilities; {@code null} for on-demand
 *     capabilities
 * @param enabled whether the binding resolves; a disabled binding behaves as absent
 * @param captureLevel exchange-audit payload capture level (ADR-0050 §7); {@code null} means
 *     the deployment default
 */
public record EndpointBindingRequest(
        @NonNull String capability,
        @NonNull String protocolFamily,
        @NonNull String version,
        @NonNull String baseUrl,
        @NonNull String path,
        @NonNull String authConfigName,
        @Nullable String schedule,
        boolean enabled,
        @Nullable PayloadCaptureLevel captureLevel) {

    public EndpointBindingRequest {
        requireNonBlank(capability, "capability");
        requireNonBlank(protocolFamily, "protocolFamily");
        requireNonBlank(version, "version");
        requireNonBlank(baseUrl, "baseUrl");
        requireNonBlank(path, "path");
        requireNonBlank(authConfigName, "authConfigName");
    }

    private static void requireNonBlank(@NonNull String value, @NonNull String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
