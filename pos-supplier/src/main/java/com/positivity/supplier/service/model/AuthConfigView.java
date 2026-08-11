package com.positivity.supplier.service.model;

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
public record AuthConfigView(
        @NonNull UUID authConfigId,
        @NonNull String name,
        @NonNull SupplierAuthType type,
        @Nullable String apiKeyHeader) {

    public AuthConfigView {
        Objects.requireNonNull(authConfigId, "authConfigId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
