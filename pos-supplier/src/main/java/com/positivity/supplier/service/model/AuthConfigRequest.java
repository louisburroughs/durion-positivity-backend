package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(
        description =
                "Create/update payload for a vendor auth config (ADR-0050 §4). Every secret field is a scheme-prefixed REFERENCE (e.g. env:VAR_NAME) resolved at call time — plaintext credentials are rejected, and references are write-only: they never appear in any response.")
public record AuthConfigRequest(
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
                        "Secret reference to the username. Required for BASIC_PLUS_APIKEY, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_EDI_USER")
        @Nullable
        String usernameRef,

        @Schema(
                description =
                        "Secret reference to the password. Required for BASIC_PLUS_APIKEY, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_EDI_PASSWORD")
        @Nullable
        String passwordRef,

        @Schema(
                description =
                        "Secret reference to the API key. Required for BASIC_PLUS_APIKEY, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_EDI_APIKEY")
        @Nullable
        String apiKeyRef,

        @Schema(
                description =
                        "Header NAME the API key is sent in — configuration data, not a secret reference. Omit to use the adapter default.",
                example = "apikey")
        @Nullable
        String apiKeyHeader,

        @Schema(
                description =
                        "Secret reference to the OAuth2 token endpoint URL. Required for OAUTH2_CLIENT_CREDENTIALS, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_TOKEN_URL")
        @Nullable
        String tokenUrlRef,

        @Schema(
                description =
                        "Secret reference to the OAuth2 client id. Required for OAUTH2_CLIENT_CREDENTIALS, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_CLIENT_ID")
        @Nullable
        String clientIdRef,

        @Schema(
                description =
                        "Secret reference to the OAuth2 client secret. Required for OAUTH2_CLIENT_CREDENTIALS, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_CLIENT_SECRET")
        @Nullable
        String clientSecretRef,

        @Schema(
                description =
                        "Secret reference to the static bearer token. Required for BEARER, must be absent otherwise. Write-only.",
                example = "env:MICHELIN_BEARER_TOKEN")
        @Nullable
        String bearerTokenRef) {

    public AuthConfigRequest {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
