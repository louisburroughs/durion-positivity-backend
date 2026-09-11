package com.positivity.tenancy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * {@link TenantRegistry} backed by a cached, shared-secret lookup against {@code pos-tenant}'s
 * internal list endpoint (plan WS4-2, decided 2026-09-10).
 *
 * <p>The snapshot starts as the static list ({@code pos.tenancy.tenants}, else the default tenant,
 * else empty) so per-tenant work can run before {@code pos-tenant} has answered once. A read
 * refreshes the snapshot lazily when the last attempt is older than {@code
 * pos.tenancy.registry.refresh}; one thread refreshes while the others keep reading the current
 * snapshot, so a slow registry never stalls the fleet. A failed fetch (transport error, non-2xx,
 * empty body, no {@code ACTIVE} tenant) keeps the last good snapshot: the registry answers with
 * what it last knew rather than starving every per-tenant job during an outage. Failures are
 * logged once on the transition to failing and once on recovery, each with the consecutive count.
 *
 * <p>The endpoint returns {@code pos-domain-events}' {@code TenantProjectionV1} shape ({@code
 * tenantId}, {@code slug}, {@code displayName}, {@code status}); only {@code ACTIVE} entries make
 * the snapshot, in the order the registry returned them.
 */
public class RemoteTenantRegistry implements TenantRegistry {

    /** Header carrying the shared secret {@code pos-tenant} checks. */
    public static final String SECRET_HEADER = "X-Tenant-Registry-Secret";

    private static final Logger log = LoggerFactory.getLogger(RemoteTenantRegistry.class);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final ParameterizedTypeReference<List<TenantSummary>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String url;
    private final String secret;
    private final Duration refresh;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile List<UUID> snapshot;
    private volatile @Nullable Instant lastAttempt;
    private volatile @Nullable Instant lastSuccess;
    private volatile long consecutiveFailures;

    /**
     * @param properties {@code pos.tenancy.*}; the static list seeds the snapshot and {@code
     *     registry.*} gives the URL, secret and refresh interval
     * @param restClient the client to fetch with; connect/read timeouts and any load balancing are
     *     the caller's (see {@code TenancyAutoConfiguration})
     * @param clock drives the refresh throttle
     */
    public RemoteTenantRegistry(
            @NonNull TenancyProperties properties, @NonNull RestClient restClient, @NonNull Clock clock) {
        TenancyProperties.Registry config = properties.getRegistry();
        this.restClient = restClient;
        this.url = config.getUrl();
        this.secret = config.getSecret();
        this.refresh = config.getRefresh();
        this.clock = clock;
        // The seed is filtered exactly as a fetched list is: a failed first fetch must not leave the
        // control-plane tenant in the snapshot either, and pos-tenant configures it as its default.
        this.snapshot = new StaticTenantRegistry(properties)
                .activeTenantIds().stream()
                        .filter(tenantId -> !PlatformTenant.ID.equals(tenantId))
                        .toList();
        if (secret.isBlank()) {
            log.warn(
                    "pos.tenancy.registry.secret is not set: every fetch from {} will be refused and the"
                            + " registry will stay on its static snapshot of {} tenants",
                    url,
                    snapshot.size());
        }
    }

    @Override
    public @NonNull List<UUID> activeTenantIds() {
        Instant now = clock.instant();
        if (isDue(now) && refreshLock.tryLock()) {
            try {
                // Re-check under the lock: another thread may have just refreshed.
                if (isDue(now)) {
                    refresh(now);
                }
            } finally {
                refreshLock.unlock();
            }
        }
        return snapshot;
    }

    /** Tenants in the current snapshot; the {@code tenancy.registry.tenants} gauge. */
    public int snapshotSize() {
        return snapshot.size();
    }

    /** Epoch seconds of the last successful fetch, {@code 0} until one has succeeded. */
    public long lastSuccessEpochSeconds() {
        Instant success = lastSuccess;
        return success == null ? 0L : success.getEpochSecond();
    }

    /** Fetch failures since the last success; {@code 0} while healthy. */
    public long consecutiveFailures() {
        return consecutiveFailures;
    }

    private boolean isDue(Instant now) {
        Instant attempt = lastAttempt;
        return attempt == null || !now.isBefore(attempt.plus(refresh));
    }

    private void refresh(Instant now) {
        lastAttempt = now;
        List<UUID> fetched;
        try {
            fetched = fetch();
        } catch (RuntimeException e) {
            long failures = ++consecutiveFailures;
            if (failures == 1) {
                log.warn(
                        "Tenant registry refresh from {} failed (failure 1); keeping the last good snapshot"
                                + " of {} tenants: {}",
                        url,
                        snapshot.size(),
                        e.toString());
            } else {
                log.debug(
                        "Tenant registry refresh from {} still failing (failure {}): {}", url, failures, e.toString());
            }
            return;
        }
        snapshot = fetched;
        lastSuccess = now;
        long failures = consecutiveFailures;
        if (failures > 0) {
            consecutiveFailures = 0;
            log.info(
                    "Tenant registry refresh from {} recovered after {} consecutive failures; {} active tenants",
                    url,
                    failures,
                    fetched.size());
        } else {
            log.debug("Tenant registry refreshed from {}: {} active tenants", url, fetched.size());
        }
    }

    private List<UUID> fetch() {
        List<TenantSummary> body = restClient
                .get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, secret)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                    // The default handler only rejects 4xx/5xx; a 3xx with a JSON body would otherwise
                    // reach body() and replace the last good snapshot.
                    throw new IllegalStateException("tenant registry answered " + response.getStatusCode());
                })
                .body(RESPONSE_TYPE);
        if (body == null || body.isEmpty()) {
            throw new IllegalStateException("empty tenant list");
        }
        // The platform tenant is control-plane data (pos-tenant, pos-security-service, both with their
        // own registries); a domain module's per-tenant work must never run under it.
        List<UUID> active = body.stream()
                .filter(tenant -> tenant.tenantId() != null
                        && !PlatformTenant.ID.equals(tenant.tenantId())
                        && STATUS_ACTIVE.equals(tenant.status()))
                .map(TenantSummary::tenantId)
                .toList();
        if (active.isEmpty()) {
            // An empty snapshot would silently stop every per-tenant job; a registry with no active
            // tenant is treated as an outage, not as an answer.
            throw new IllegalStateException("no ACTIVE tenant in a list of " + body.size());
        }
        return active;
    }

    /**
     * One entry of the list endpoint: the {@code TenantProjectionV1} projection, which allows
     * additive fields, so an unknown property from a newer {@code pos-tenant} is ignored rather
     * than failing the fetch and stranding every caller on a stale snapshot.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TenantSummary(
            @Nullable UUID tenantId,
            @Nullable String slug,
            @Nullable String displayName,
            @Nullable String status) {}
}
