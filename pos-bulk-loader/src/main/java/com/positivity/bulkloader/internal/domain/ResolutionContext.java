package com.positivity.bulkloader.internal.domain;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;

/**
 * What a loader strategy needs in order to turn the business keys in a file into the ids the owning
 * service's ingest endpoint expects.
 *
 * <p>Fixtures and customer-supplied files name things the way people do — an employee number, a
 * location code, a storage location's name, a catalog category. Ingest endpoints take UUIDs. Until
 * this existed the loader had no way to bridge the two, so every domain whose file was keyed by
 * name had to stay outside the loader and be replayed row by row by a script, or have its file
 * rewritten client-side before upload.
 *
 * <p>Implementations look up against the live services, so results are memoized per job: a file of
 * 500 rows naming five locations should cost five calls, not 500.
 */
public interface ResolutionContext {

    /** The location the bulk-load job is scoped to, as supplied when the job was created. */
    @NonNull
    UUID jobLocationId();

    /**
     * GETs from a sibling service, relaying the operator's credentials.
     *
     * @param serviceId the Eureka service id, e.g. {@code location}
     * @param uri the path on that service, already encoded
     * @param responseType the expected body type
     * @return the body, or empty when the call failed or returned nothing — callers decide whether
     *     that is a row failure or a legitimate absence, so this never throws for a 4xx
     */
    @NonNull
    <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType);

    /**
     * Runs {@code loader} once per distinct key for the life of the job and reuses the result.
     *
     * <p>The value is cached even when it is empty: a name that does not resolve will not resolve
     * on the next row either, and re-asking for every row of a large file turns one bad key into
     * hundreds of calls.
     */
    @NonNull
    <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader);
}
