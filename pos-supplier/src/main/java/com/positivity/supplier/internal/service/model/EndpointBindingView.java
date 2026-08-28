package com.positivity.supplier.internal.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.Set;
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
 * @param redactionClassifications data classifications additionally redacted from {@code REDACTED}
 *     captures of this binding; empty means credential redaction only
 */
@Schema(description = "Read model of a capability endpoint binding.")
public record EndpointBindingView(
        @Schema(
                description = "Identity of the endpoint binding (UUIDv7).",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60")
        @NonNull
        UUID bindingId,

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
                description = "Adapter version key within the protocol family. Free-form data, deliberately not an enum"
                        + " (ADR-0051 §3) so a vendor's new norm needs no code change. NOT validated on write:"
                        + " a key with no registered codec is accepted here and then resolves to"
                        + " CAPABILITY_NOT_CONFIGURED on every call, so it must match a codec exactly."
                        + " Keys shipped today: A2_5, B2_1, B3_3, B4_0, C1_0, C1_1, C1_2, S2S_V1.",
                example = "A2_5")
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
                description = "Whether THIS binding resolves. A disabled binding behaves exactly as an absent one:"
                        + " only this capability stops resolving for this supplier, and it reports the typed"
                        + " CAPABILITY_NOT_CONFIGURED outcome. This is a per-capability toggle, NOT a"
                        + " supplier-wide kill switch -- for that, disable the vendor profile itself.",
                example = "true")
        boolean enabled,

        @Schema(
                description =
                        "Exchange-audit payload capture level for this binding (ADR-0050 §7). Exchange metadata is always retained regardless. Omit to use the deployment default.",
                example = "REDACTED")
        @Nullable
        PayloadCaptureLevel captureLevel,

        @Schema(
                description = "Data classifications whose named fields are additionally redacted from REDACTED"
                        + " captures of this binding (ADR-0050 §7 minimization). Empty means credential"
                        + " redaction only.",
                example = "[\"CUSTOMER_IDENTIFIER\"]")
        @NonNull
        Set<RedactionClassification> redactionClassifications) {

    public EndpointBindingView {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(protocolFamily, "protocolFamily must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(authConfigName, "authConfigName must not be null");
        Objects.requireNonNull(redactionClassifications, "redactionClassifications must not be null");
        redactionClassifications = Set.copyOf(redactionClassifications);
    }
}
