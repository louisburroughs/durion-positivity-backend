package com.positivity.supplier.internal.config;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment YAML binding of the {@code supplier.profiles} list (ADR-0050 §6, architecture
 * doc §7). Specs are raw string keys deliberately — canonical value validation (auth types,
 * capabilities, protocol families, secret-reference shape) happens in
 * {@link SupplierYamlBootstrap} with clear startup errors, never lenient coercion.
 *
 * <p>Secrets are references only ({@code scheme:key}); the YAML never contains plaintext
 * credentials and the reconciler rejects plaintext-looking values at startup.
 *
 * @param profiles the YAML-managed vendor profile specs; {@code null}/empty when the
 *     deployment manages all profiles through the admin API
 */
@ConfigurationProperties(prefix = "supplier")
public record SupplierProfileProperties(@Nullable List<ProfileSpec> profiles) {

    /**
     * One YAML-managed vendor profile (architecture doc §7).
     *
     * @param key the {@code supplierRef} alias (identity is {@code vendorProfileId} UUID)
     * @param displayName admin-screen display name
     * @param enabled whether the profile's bindings resolve; {@code null} = {@code true}
     * @param protocolDefaults profile-level protocol defaults
     * @param accounts commercial account numbers (credentials live in {@code auth})
     * @param auth auth configs carrying secret references
     * @param bindings capability endpoint bindings
     * @param sandbox environment overlay
     */
    public record ProfileSpec(
            @Nullable String key,
            @Nullable String displayName,
            @Nullable Boolean enabled,
            @Nullable ProtocolDefaults protocolDefaults,
            @Nullable Accounts accounts,
            @Nullable List<AuthSpec> auth,
            @Nullable List<BindingSpec> bindings,
            @Nullable Sandbox sandbox) {}

    /**
     * Profile-level protocol defaults (§7 {@code protocolDefaults}).
     *
     * @param family default protocol family key for bindings that do not name their own
     * @param connectTimeoutMs default connect timeout
     * @param readTimeoutMs default read timeout
     * @param retry retry defaults
     */
    public record ProtocolDefaults(
            @Nullable String family,
            @Nullable Integer connectTimeoutMs,
            @Nullable Integer readTimeoutMs,
            @Nullable Retry retry) {}

    /**
     * Retry defaults (§7 {@code protocolDefaults.retry}).
     *
     * @param maxAttempts pre-send retry budget
     * @param backoff backoff strategy key ({@code FIXED}/{@code EXPONENTIAL})
     */
    public record Retry(
            @Nullable Integer maxAttempts, @Nullable String backoff) {}

    /**
     * Commercial accounts (§7 {@code accounts}). {@code sellerPartyId}/{@code sellerAgencyCode}
     * are accepted for §7 shape fidelity but not yet persisted (adapter-facing, CAP-317
     * slice 3 follow-up).
     *
     * @param billing the invoicing/settlement account (one per profile)
     * @param delivery pos-location to delivery-account mappings
     * @param sellerPartyId vendor seller party identifier
     * @param sellerAgencyCode vendor seller agency code
     */
    public record Accounts(
            @Nullable Billing billing,
            @Nullable List<Delivery> delivery,
            @Nullable String sellerPartyId,
            @Nullable String sellerAgencyCode) {}

    /**
     * Billing account (§7 {@code accounts.billing}).
     *
     * @param accountNumber vendor billing account number
     * @param agencyCode identification agency code (e.g. EAN/GLN)
     */
    public record Billing(
            @Nullable String accountNumber, @Nullable String agencyCode) {}

    /**
     * Delivery account mapping (§7 {@code accounts.delivery[]}).
     *
     * @param locationId pos-location UUID of the receiving location
     * @param accountNumber vendor delivery account number
     * @param agencyCode identification agency code
     */
    public record Delivery(
            @Nullable UUID locationId,
            @Nullable String accountNumber,
            @Nullable String agencyCode) {}

    /**
     * Auth config spec (§7 {@code auth[]}) — secret references only, never plaintext.
     *
     * @param name unique-per-profile auth config name
     * @param type auth scheme key ({@code BASIC_PLUS_APIKEY}/{@code OAUTH2_CLIENT_CREDENTIALS}/{@code BEARER})
     * @param usernameRef secret reference of the Basic username
     * @param passwordRef secret reference of the Basic password
     * @param apiKeyHeader plain header name the API key is sent under (not a secret)
     * @param apiKeyRef secret reference of the API key
     * @param tokenUrlRef secret reference of the OAuth2 token endpoint URL
     * @param clientIdRef secret reference of the OAuth2 client id
     * @param clientSecretRef secret reference of the OAuth2 client secret
     * @param bearerTokenRef secret reference of the static bearer token
     */
    public record AuthSpec(
            @Nullable String name,
            @Nullable String type,
            @Nullable String usernameRef,
            @Nullable String passwordRef,
            @Nullable String apiKeyHeader,
            @Nullable String apiKeyRef,
            @Nullable String tokenUrlRef,
            @Nullable String clientIdRef,
            @Nullable String clientSecretRef,
            @Nullable String bearerTokenRef) {}

    /**
     * Capability endpoint binding spec (§7 {@code bindings[]}).
     *
     * @param capability canonical capability key
     * @param family protocol family key; {@code null} falls back to
     *     {@code protocolDefaults.family}
     * @param version norm-version key (data, not an enum — ADR-0051 §3)
     * @param baseUrl endpoint base URL
     * @param path endpoint path relative to {@code baseUrl}
     * @param auth name of the profile auth config authenticating this binding
     * @param schedule cron expression for batch capabilities
     * @param enabled whether the binding resolves; {@code null} = {@code true}
     * @param captureLevel exchange-audit payload capture level key (ADR-0050 §7)
     * @param redactions data classification keys additionally redacted from {@code REDACTED}
     *     captures of this binding (ADR-0050 §7 minimization); {@code null}/empty = credential
     *     redaction only
     */
    public record BindingSpec(
            @Nullable String capability,
            @Nullable String family,
            @Nullable String version,
            @Nullable String baseUrl,
            @Nullable String path,
            @Nullable String auth,
            @Nullable String schedule,
            @Nullable Boolean enabled,
            @Nullable String captureLevel,
            @Nullable List<String> redactions) {}

    /**
     * Environment overlay (§7 {@code sandbox}).
     *
     * @param enabled whether the profile runs against the vendor's sandbox environment
     * @param baseUrlOverride sandbox base URL override
     */
    public record Sandbox(
            @Nullable Boolean enabled, @Nullable String baseUrlOverride) {}
}
