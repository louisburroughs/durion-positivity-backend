package com.positivity.supplier.internal.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a vendor auth config (ADR-0050 §4). <strong>Deliberately excludes every
 * credential reference field</strong> — secret references are write-only
 * ({@link AuthConfigRequest}) and never serialize into responses; this record has no place to
 * put them.
 *
 * @param authConfigId auth config identity
 * @param name unique-per-profile auth config name; referenced by endpoint bindings
 * @param type authentication scheme
 * @param apiKeyHeader plain header <em>name</em> the API key is sent under (e.g.
 *     {@code apikey}) — configuration data, not a secret, hence readable here while every
 *     {@code *Ref} field stays write-only; {@code null} means the adapter default
 */
@Schema(
        description =
                "Read model of a vendor auth config. Carries no secret reference fields at all, by shape — credential references never serialize into responses (ADR-0050 §4).")
public record AuthConfigView(
        @Schema(description = "Identity of the auth config (UUIDv7).", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61")
        @NonNull
        UUID authConfigId,

        @Schema(
                description =
                        "Auth config name, unique within the vendor profile. Endpoint bindings reference it by this name.",
                example = "ediwheel-basic")
        @NonNull
        String name,

        @Schema(
                description =
                        "Credential scheme this config describes. Determines exactly which secret reference fields are required and which must be absent (ADR-0050 §4).",
                example = "BASIC_PLUS_APIKEY")
        @NonNull
        SupplierAuthType type,

        @Schema(
                description =
                        "Header NAME the API key is sent in — configuration data, not a secret reference. Omit to use the adapter default.",
                example = "apikey")
        @Nullable
        String apiKeyHeader) {

    public AuthConfigView {
        Objects.requireNonNull(authConfigId, "authConfigId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
