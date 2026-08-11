package com.positivity.supplier.internal.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OAuth2 {@code client_credentials} (ADR-0050 §4): exchanges a client id/secret for a short-lived
 * access token and sends it as a bearer.
 *
 * <p><strong>Token cache, keyed per {@code authConfigId}.</strong> Two profiles may legitimately
 * use the same token URL with different clients, so the cache key is the auth config's identity,
 * never the URL. Tokens are refreshed early by an expiry skew: a token that expires in-flight
 * produces a 401 the caller cannot distinguish from a real credential failure.
 *
 * <p><strong>Single-flight.</strong> The scheduler can fire many bindings of one profile at once.
 * Without coordination each would mint its own token, and vendors commonly rate-limit or
 * invalidate prior tokens on the token endpoint. A per-key lock with a re-check inside means
 * exactly one request per key refreshes while the others wait and reuse the result. The lock is
 * per {@code authConfigId}, so an unrelated supplier's refresh is never blocked.
 *
 * <p>The cached value is a vendor-issued, short-lived artifact — not a stored credential — which
 * is why caching it is legitimate where caching a password would not be. The client id and secret
 * themselves are resolved from references on every refresh and never held.
 */
@Component
public class OAuth2ClientCredentialsAuthStrategy implements SupplierAuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientCredentialsAuthStrategy.class);

    /** Assumed lifetime when a vendor omits {@code expires_in}; deliberately short. */
    private static final Duration FALLBACK_TOKEN_LIFETIME = Duration.ofMinutes(5);

    private final SecretSchemeRegistry secretSchemeRegistry;
    private final RestClient tokenClient;
    private final Clock clock;
    private final Duration expirySkew;

    private final Map<UUID, CachedToken> tokensByAuthConfigId = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    public OAuth2ClientCredentialsAuthStrategy(
            @NonNull SecretSchemeRegistry secretSchemeRegistry,
            RestClient.@NonNull Builder restClientBuilder,
            @NonNull Clock clock,
            @Value("${pos.supplier.oauth2.expiry-skew-seconds:30}") long expirySkewSeconds) {
        this.secretSchemeRegistry = Objects.requireNonNull(secretSchemeRegistry, "secretSchemeRegistry");
        this.tokenClient =
                Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expirySkew = Duration.ofSeconds(expirySkewSeconds);
    }

    @Override
    @NonNull
    public SupplierAuthType supportedType() {
        return SupplierAuthType.OAUTH2_CLIENT_CREDENTIALS;
    }

    @Override
    public void apply(@NonNull HttpHeaders headers, @NonNull SupplierAuthConfigEntity authConfig) {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(authConfig, "authConfig must not be null");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(authConfig));
    }

    /**
     * Drops any cached token for an auth config — the only override of this hook, because OAuth2 is the only
     * auth type that caches anything.
     *
     * <p>Reached from two places, both through {@link SupplierAuthStrategies#invalidateCachedCredential}:
     * {@code SupplierBaseClient} when a vendor answers 401, so a token revoked before its stated expiry is
     * not re-sent until it would have expired; and an administrator changing or deleting the auth config, so
     * a rotated client secret takes effect on the next exchange instead of within the hour.
     *
     * <p>Only the token is dropped, not the {@link ReentrantLock} for the key. Removing the lock would let a
     * refresh already waiting on it acquire a <em>different</em> lock instance and defeat single-flighting at
     * precisely the moment several callers are about to refresh at once. The locks are bounded by the number
     * of auth configs, so retaining them costs nothing worth having.
     */
    @Override
    public void invalidateCachedCredential(@NonNull UUID authConfigId) {
        tokensByAuthConfigId.remove(Objects.requireNonNull(authConfigId, "authConfigId"));
    }

    @NonNull
    private String accessToken(@NonNull SupplierAuthConfigEntity authConfig) {
        UUID key = requireKey(authConfig);

        CachedToken cached = tokensByAuthConfigId.get(key);
        if (isUsable(cached)) {
            return cached.accessToken();
        }

        ReentrantLock lock = refreshLocks.computeIfAbsent(key, id -> new ReentrantLock());
        lock.lock();
        try {
            // Re-check inside the lock: a concurrent caller may have refreshed while we waited,
            // which is the whole point of single-flighting.
            CachedToken current = tokensByAuthConfigId.get(key);
            if (isUsable(current)) {
                return current.accessToken();
            }
            CachedToken minted = requestToken(authConfig);
            tokensByAuthConfigId.put(key, minted);
            return minted.accessToken();
        } finally {
            lock.unlock();
        }
    }

    private boolean isUsable(@Nullable CachedToken token) {
        // Skew-adjusted: a token expiring inside the skew window is treated as already expired so
        // it cannot expire mid-flight and surface as an indistinguishable 401.
        return token != null && Instant.now(clock).plus(expirySkew).isBefore(token.expiresAt());
    }

    @NonNull
    private CachedToken requestToken(@NonNull SupplierAuthConfigEntity authConfig) {
        String tokenUrl = resolve(authConfig, "tokenUrlRef", authConfig.getTokenUrlRef());
        String clientId = resolve(authConfig, "clientIdRef", authConfig.getClientIdRef());
        String clientSecret = resolve(authConfig, "clientSecretRef", authConfig.getClientSecretRef());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        TokenResponse response;
        try {
            // Client credentials go in the Basic header, not the body: RFC 6749 §2.3.1 makes that
            // the required form and vendors vary on whether they accept body parameters at all.
            String basic = Base64.getEncoder()
                    .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            response = tokenClient
                    .post()
                    .uri(tokenUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                // The vendor actively rejected our client id/secret. Retrying would hammer their
                // token endpoint with credentials that will keep being refused, so this stays a
                // configuration error -- but with its own code, so it is distinguishable from an
                // absent reference and does not send an operator hunting for a missing env var.
                throw new SupplierConfigurationException(
                        SupplierConfigurationException.AUTH_CREDENTIALS_REJECTED,
                        "OAuth2 token endpoint rejected the configured client credentials for auth config '"
                                + authConfig.getName() + "' with HTTP " + status
                                + "; the credentials resolved successfully but the vendor refused them");
            }
            // 429, 5xx and anything else: the vendor's token endpoint is unhealthy, not our config.
            throw new SupplierAuthTransportException(
                    authConfig.getName(),
                    "OAuth2 token endpoint returned HTTP " + status + " for auth config '" + authConfig.getName() + "'",
                    ex);
        } catch (RestClientException ex) {
            // Connection refused, timeout, unparseable response: transport, not configuration.
            // Cause retained -- discarding it left operators with no way to tell a DNS failure from
            // a TLS handshake failure.
            throw new SupplierAuthTransportException(
                    authConfig.getName(),
                    "OAuth2 token request failed at transport level for auth config '" + authConfig.getName() + "' ("
                            + ex.getClass().getSimpleName() + ")",
                    ex);
        }

        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()) {
            // A 2xx with no token is a vendor contract violation. Not retryable, and not the
            // operator's configuration either, so it gets its own non-blaming code.
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.AUTH_TOKEN_RESPONSE_INVALID,
                    "OAuth2 token endpoint answered successfully but supplied no access_token for auth config '"
                            + authConfig.getName() + "'");
        }

        Duration lifetime = response.expiresIn() == null || response.expiresIn() <= 0
                ? FALLBACK_TOKEN_LIFETIME
                : Duration.ofSeconds(response.expiresIn());
        // Never log the token, only its lifetime.
        log.debug(
                "Issued OAuth2 access token for auth config '{}' valid for {}s",
                authConfig.getName(),
                lifetime.toSeconds());
        return new CachedToken(response.accessToken(), Instant.now(clock).plus(lifetime));
    }

    @NonNull
    private UUID requireKey(@NonNull SupplierAuthConfigEntity authConfig) {
        UUID id = authConfig.getId();
        if (id == null) {
            // A transient auth config has no stable cache key; caching by anything else risks
            // handing one supplier's token to another.
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.AUTH_CONFIG_MISSING,
                    "Auth config '" + authConfig.getName() + "' has no identity yet; it must be persisted before use");
        }
        return id;
    }

    @NonNull
    private String resolve(@NonNull SupplierAuthConfigEntity authConfig, @NonNull String field, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.AUTH_CONFIG_MISSING,
                    "Auth config '" + authConfig.getName() + "' of type " + supportedType() + " is missing " + field
                            + " (ADR-0050 §4)");
        }
        return secretSchemeRegistry.resolve(reference);
    }

    /** A minted access token and the instant it stops being valid. */
    private record CachedToken(
            @NonNull String accessToken, @NonNull Instant expiresAt) {

        /**
         * Overridden because a record's generated {@code toString} renders every component: any debug
         * log, assertion message or exception that happened to include this object would print the
         * bearer token verbatim.
         */
        @Override
        public String toString() {
            return "CachedToken[accessToken=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    /** Token endpoint response; unknown properties are ignored by Jackson defaults. */
    record TokenResponse(
            @JsonProperty("access_token") @Nullable String accessToken,
            @JsonProperty("token_type") @Nullable String tokenType,
            @JsonProperty("expires_in") @Nullable Long expiresIn) {

        /** Overridden for the same reason as {@link CachedToken}: it would render the token. */
        @Override
        public String toString() {
            return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
        }
    }
}
