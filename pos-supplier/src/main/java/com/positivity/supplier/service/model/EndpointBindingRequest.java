package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.Set;
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
 * @param redactionClassifications data classifications additionally redacted from {@code REDACTED}
 *     captures of this binding (ADR-0050 §7 minimization); {@code null} or empty means credential
 *     redaction only
 */
@Schema(
        description = "Create/update payload for a capability endpoint binding (ADR-0050 §3): at most one binding per"
                + " capability per profile. An absent or disabled binding means the capability is disabled for"
                + " the supplier and resolves to the typed CAPABILITY_NOT_CONFIGURED status -- a value of the"
                + " capability response's own status enum, returned with HTTP 200, not an error."
                + " Do not confuse it with the ApiError code SUPPLIER_CAPABILITY_NOT_CONFIGURED, which is the"
                + " same condition escaping as a 4xx and indicates a defect, not a normal outcome.")
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
                description = "Adapter version key within the protocol family. Free-form data, deliberately not an enum"
                        + " (ADR-0051 §3) so a vendor's new norm needs no code change. NOT validated on write:"
                        + " a key with no registered codec is accepted here and then resolves to"
                        + " CAPABILITY_NOT_CONFIGURED on every call, so it must match a codec exactly."
                        + " Keys shipped today: A2_5, B2_1, B3_3, B4_0, C1_0, C1_1, C1_2, S2S_V1."
                        + " At most 64 characters: the exchange-audit trail stores this key at that width, and"
                        + " a longer one would make every audit write for the binding fail.",
                example = "A2_5")
        @NonNull
        @Size(max = 64)
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
                        + " captures of this binding (ADR-0050 §7 minimization). Credential redaction always"
                        + " applies and cannot be configured away; classifications only narrow what a REDACTED"
                        + " capture retains. Name-based: for positional vendor formats (EDIFACT segments) they"
                        + " redact nothing and METADATA_ONLY is the only level guaranteeing no content is"
                        + " retained. Omit for credential redaction only.",
                example = "[\"CUSTOMER_IDENTIFIER\"]")
        @Nullable
        Set<RedactionClassification> redactionClassifications) {

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
