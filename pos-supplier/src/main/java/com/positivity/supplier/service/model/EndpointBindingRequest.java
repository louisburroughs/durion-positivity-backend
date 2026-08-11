package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(
        description =
                "Create/update payload for a capability endpoint binding (ADR-0050 §3): at most one binding per capability per profile. An absent binding means the capability is disabled for the supplier and resolves to the typed SUPPLIER_CAPABILITY_NOT_CONFIGURED outcome.")
public record EndpointBindingRequest(
        @Schema(
                description =
                        "Canonical supplier capability key this binding serves. Unknown keys are rejected with SUPPLIER_UNKNOWN_CAPABILITY.",
                example = "STOCK_INQUIRY")
        @NonNull
        String capability,

        @Schema(
                description =
                        "Canonical protocol family key of the adapter to use. Unknown keys are rejected with SUPPLIER_UNKNOWN_PROTOCOL_FAMILY.",
                example = "EDIWHEEL_A25")
        @NonNull
        String protocolFamily,

        @Schema(
                description =
                        "Adapter version within the protocol family. Free-form data, deliberately not an enum (ADR-0051 §3).",
                example = "2.5")
        @NonNull
        String version,

        @Schema(
                description = "Base URL of the vendor endpoint for this capability.",
                example = "https://edi.michelin.example/a25")
        @NonNull
        String baseUrl,

        @Schema(description = "Path appended to the base URL for this capability.", example = "/stock/inquiry") @NonNull
        String path,

        @Schema(
                description =
                        "Name of the auth config on the same profile that this binding authenticates with. Must already exist.",
                example = "ediwheel-basic")
        @NonNull
        String authConfigName,

        @Schema(
                description =
                        "Cron expression driving scheduled runs of this binding. Omit for request-driven capabilities.",
                example = "0 0 3 * * *")
        @Nullable
        String schedule,

        @Schema(
                description =
                        "Whether the profile's bindings resolve at all. A disabled profile resolves every capability to a typed not-configured outcome.",
                example = "true")
        boolean enabled,

        @Schema(
                description =
                        "Exchange-audit payload capture level for this binding (ADR-0050 §7). Exchange metadata is always retained regardless. Omit to use the deployment default.",
                example = "REDACTED")
        @Nullable
        PayloadCaptureLevel captureLevel) {

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
