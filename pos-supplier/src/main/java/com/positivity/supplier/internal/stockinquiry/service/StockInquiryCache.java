package com.positivity.supplier.internal.stockinquiry.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Short-TTL cache of per-article availability answers (DECISION-POSITIVITY-008).
 *
 * <h2>The delivery location is part of the key, not a detail of it</h2>
 *
 * Availability is consignee-specific: the same article can be in stock for one branch and not for
 * the branch down the road, because the vendor answers against the address it will ship to. A cache
 * keyed on article and vendor alone would serve one location's answer to another — a customer told
 * "available today" about stock that is four hundred kilometres from the fitter who will fit it.
 * The key carries the location for that reason, and the type will not let a caller omit it.
 *
 * <h2>Only answers are cached</h2>
 *
 * A vendor that was unreachable a second ago may be reachable now, and an article the vendor could
 * not determine availability for is not a fact worth remembering. Caching those would extend one
 * failure into a minute of identical failures for every customer looking at the page. Only
 * {@code AVAILABLE} and {@code UNAVAILABLE} — the two cases where the vendor actually said
 * something — are stored.
 *
 * <p>{@code NOT_LISTED} is deliberately <em>not</em> cached either, even though it is stable. It is
 * usually the signal that a catalogue mapping is wrong, and an operator who fixes the mapping should
 * see the fix on the next page load rather than a minute later, wondering whether it worked.
 */
@Component
public class StockInquiryCache {

    /**
     * Ceiling on entries. A cache this short-lived exists to absorb the repeat views of one product
     * page, not to hold a catalogue; the bound is what keeps a burst of distinct articles from
     * turning a cache into a memory leak.
     */
    private static final int MAX_ENTRIES = 20_000;

    private final Cache<Key, Answer> entries;
    private final boolean enabled;

    public StockInquiryCache(
            @Value("${pos.supplier.stockinquiry.cache-enabled:true}") boolean enabled,
            @Value("${pos.supplier.stockinquiry.cache-ttl:PT60S}") Duration ttl) {
        this.enabled = enabled && !ttl.isZero() && !ttl.isNegative();
        this.entries = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(this.enabled ? ttl : Duration.ofMillis(1))
                .build();
    }

    /** Whether caching is switched on for this deployment. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The cached answer for one article at one location, or null when there is none. */
    @Nullable
    public Answer get(@NonNull Key key) {
        return enabled ? entries.getIfPresent(key) : null;
    }

    /**
     * Stores an answer, if it is the kind of answer worth remembering.
     *
     * <p>Silently ignores anything else, so a caller cannot accidentally extend a vendor outage by
     * caching it.
     */
    public void put(
            @NonNull Key key,
            SupplierStockInquiryResult.@NonNull Line line,
            @NonNull Instant fetchedAt,
            @NonNull Instant asOf) {
        if (!enabled) {
            return;
        }
        if (line.status() != SupplierStockInquiryResult.LineStatus.AVAILABLE
                && line.status() != SupplierStockInquiryResult.LineStatus.UNAVAILABLE) {
            return;
        }
        entries.put(key, new Answer(line, fetchedAt, asOf));
    }

    /** Drops everything; used by tests and by an operator-triggered refresh. */
    public void clear() {
        entries.invalidateAll();
    }

    /**
     * What identifies one cached answer.
     *
     * @param vendorProfileId the vendor asked
     * @param deliveryLocationId the receiving location the answer is about — never optional
     * @param articleKey the article identity as inquired, normalised by the caller
     */
    public record Key(
            @NonNull UUID vendorProfileId,
            @NonNull UUID deliveryLocationId,
            @NonNull String articleKey) {

        public Key {
            Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
            Objects.requireNonNull(deliveryLocationId, "deliveryLocationId must not be null");
            Objects.requireNonNull(articleKey, "articleKey must not be null");
        }
    }

    /**
     * A remembered answer.
     *
     * <p>Two instants because they are two facts (#1637 decision 1). {@code fetchedAt} is when this
     * module obtained the answer — the age of the cache entry — and {@code asOf} is the observation
     * time the vendor stated for it, when the norm carries one. A2.5 states none, in which case the
     * caller stamps both with the fetch instant; a norm that does state one must not have its
     * statement overwritten by our clock. Both are carried so a cached answer reports each fact's
     * own age rather than the age of the cache hit.
     *
     * @param line what the vendor said
     * @param fetchedAt when this module obtained the answer from the vendor
     * @param asOf the vendor's stated observation instant, or the fetch instant when it stated none
     */
    public record Answer(
            SupplierStockInquiryResult.@NonNull Line line,
            @NonNull Instant fetchedAt,
            @NonNull Instant asOf) {

        public Answer {
            Objects.requireNonNull(line, "line must not be null");
            Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
            Objects.requireNonNull(asOf, "asOf must not be null");
        }
    }
}
