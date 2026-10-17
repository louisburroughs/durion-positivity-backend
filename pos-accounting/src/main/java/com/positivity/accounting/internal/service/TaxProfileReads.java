package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A country's tax profile for a <em>read</em> (#2679): from a short cache while fresh, else asked of pos-tax; empty
 * when pos-tax cannot answer, and that miss is remembered briefly so one slow or down pos-tax costs a burst of reads
 * one timeout, not one per read. The one place the degraded-read policy lives (labels are left null, never invented).
 * Never for a decision: a posting asks {@link TaxProfileClient#taxTypes} itself and holds when it cannot.
 */
@Component
@Slf4j
public class TaxProfileReads {

    static final Duration FRESH_FOR = Duration.ofSeconds(60);
    static final Duration MISS_REMEMBERED_FOR = Duration.ofSeconds(15);

    private record Cached(TaxProfileClient.@Nullable TaxTypes profile, Instant expires) {}

    private final TaxProfileClient taxProfiles;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TaxProfileReads(@NonNull TaxProfileClient taxProfiles, @NonNull Clock clock) {
        this.taxProfiles = taxProfiles;
        this.clock = clock;
    }

    /** The country's profile, or empty when pos-tax did not answer (within the last few seconds, or now). */
    public Optional<TaxProfileClient.TaxTypes> taxTypesIfAvailable(@NonNull String countryCode) {
        Instant now = Instant.now(clock);
        Cached cached = cache.get(countryCode);
        if (cached != null && now.isBefore(cached.expires())) {
            return Optional.ofNullable(cached.profile());
        }
        try {
            TaxProfileClient.TaxTypes profile = taxProfiles.taxTypes(countryCode);
            cache.put(countryCode, new Cached(profile, now.plus(FRESH_FOR)));
            return Optional.ofNullable(profile);
        } catch (TaxServiceUnavailableException unavailable) {
            log.warn("Read without the tax profile of {}: the tax service did not answer", countryCode);
            cache.put(countryCode, new Cached(null, now.plus(MISS_REMEMBERED_FOR)));
            return Optional.empty();
        }
    }
}
