package com.positivity.supplier.service.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Create/update payload for a vendor auth config (ADR-0050 §4). All {@code *Ref} fields are
 * secret <em>references</em> ({@code env:} / secret-store keys, e.g.
 * {@code "env:SUPPLIER_MICHELIN_PASSWORD"}) resolved at call time — never plaintext
 * credentials. These fields are <strong>write-only</strong>: they exist here and are excluded
 * from {@link AuthConfigView} entirely; they never serialize into API responses and never
 * appear in logs or the exchange audit.
 *
 * <p>Which references a given {@link SupplierAuthType} requires is documented on the enum;
 * per-type completeness is validated by the implementation (bad input maps to 400 per
 * ADR-0017).
 *
 * @param name unique-per-profile auth config name; endpoint bindings reference it as their
 *     {@code authConfigName}; never blank
 * @param type authentication scheme
 * @param usernameRef secret reference of the Basic username
 * @param passwordRef secret reference of the Basic password
 * @param apiKeyRef secret reference of the API key
 * @param apiKeyHeader plain header <em>name</em> the API key is sent under (e.g.
 *     {@code apikey}) — configuration data, not a secret, so unlike the {@code *Ref} fields
 *     it round-trips onto {@link AuthConfigView}; {@code null} means the adapter default
 * @param tokenUrlRef secret reference of the OAuth2 token endpoint URL
 * @param clientIdRef secret reference of the OAuth2 client id
 * @param clientSecretRef secret reference of the OAuth2 client secret
 * @param bearerTokenRef secret reference of the static bearer token
 */
public record AuthConfigRequest(
        @NonNull String name,
        @NonNull SupplierAuthType type,
        @Nullable String usernameRef,
        @Nullable String passwordRef,
        @Nullable String apiKeyRef,
        @Nullable String apiKeyHeader,
        @Nullable String tokenUrlRef,
        @Nullable String clientIdRef,
        @Nullable String clientSecretRef,
        @Nullable String bearerTokenRef) {

    public AuthConfigRequest {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
