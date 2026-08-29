package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.domain.ResolutionContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A {@link ResolutionContext} backed by load-balanced REST calls to sibling services.
 *
 * <p>Created once per step, so its cache lives exactly as long as the file being loaded: long
 * enough that a location code appearing on 500 rows costs one call, and short enough that a
 * long-lived loader never serves a stale id to a later job.
 *
 * <p>Not thread-safe, which is deliberate — a step's processor runs single-threaded, and a shared
 * cache across concurrent steps would leak one job's lookups into another's.
 */
@Slf4j
public class RestResolutionContext implements ResolutionContext {

    private final RestClient.Builder restClientBuilder;
    private final AuthorizationHeaderRelay headerRelay;
    private final UUID jobLocationId;

    private final Map<String, RestClient> clientsByServiceId = new HashMap<>();
    private final Map<String, Optional<?>> cache = new HashMap<>();

    public RestResolutionContext(
            RestClient.Builder restClientBuilder,
            @NonNull AuthorizationHeaderRelay headerRelay,
            @NonNull UUID jobLocationId) {
        this.restClientBuilder = restClientBuilder;
        this.headerRelay = headerRelay;
        this.jobLocationId = jobLocationId;
    }

    @Override
    @NonNull
    public UUID jobLocationId() {
        return jobLocationId;
    }

    @Override
    @NonNull
    public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
        RestClient client = clientsByServiceId.computeIfAbsent(
                serviceId, id -> restClientBuilder.baseUrl("http://" + id).build());
        try {
            RestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
            headerRelay.apply(spec);
            return Optional.ofNullable(spec.retrieve().body(responseType));
        } catch (RestClientException e) {
            // Returned as an absence rather than rethrown: one unresolvable reference should fail
            // its own rows through validation, not abandon the whole file. The cause is logged
            // because "not found" and "the service is down" look identical to the caller, and only
            // one of them is the fixture's fault.
            log.warn("Resolution lookup failed: GET http://{}{} — {}", serviceId, uri, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deliberately not {@code computeIfAbsent}: a resolution often needs another resolution to get
     * going — a storage location's name means nothing until its site's id is known — and running a
     * loader inside {@code computeIfAbsent} mutates the map while it is computing, which
     * {@link HashMap} answers with a {@link java.util.ConcurrentModificationException}. Looking up,
     * computing, then storing lets lookups nest. The worst a race could cost here is a duplicate
     * lookup, and the step is single-threaded anyway.
     */
    @Override
    @NonNull
    @SuppressWarnings("unchecked")
    public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
        Optional<?> cached = cache.get(cacheKey);
        if (cached != null) {
            return (Optional<R>) cached;
        }
        Optional<R> loaded = loader.get();
        cache.put(cacheKey, loaded);
        return loaded;
    }
}
