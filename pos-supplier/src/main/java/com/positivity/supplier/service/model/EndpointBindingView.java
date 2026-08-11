package com.positivity.supplier.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a capability endpoint binding (ADR-0050 §3). Key fields are canonical string
 * keys, mirroring {@link EndpointBindingRequest}.
 *
 * @param bindingId binding identity
 * @param capability canonical capability key; never blank
 * @param protocolFamily canonical protocol family key; never blank
 * @param version norm-version key within the family; never blank
 * @param baseUrl endpoint base URL; never blank
 * @param path endpoint path relative to {@code baseUrl}; never blank
 * @param authConfigName name of the profile auth config authenticating this binding
 * @param schedule cron expression for batch capabilities; {@code null} for on-demand
 *     capabilities
 * @param enabled whether the binding resolves; a disabled binding behaves as absent
 * @param captureLevel exchange-audit payload capture level; {@code null} means the deployment
 *     default
 */
public record EndpointBindingView(
        @NonNull UUID bindingId,
        @NonNull String capability,
        @NonNull String protocolFamily,
        @NonNull String version,
        @NonNull String baseUrl,
        @NonNull String path,
        @NonNull String authConfigName,
        @Nullable String schedule,
        boolean enabled,
        @Nullable PayloadCaptureLevel captureLevel) {

    public EndpointBindingView {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(protocolFamily, "protocolFamily must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(authConfigName, "authConfigName must not be null");
    }
}
