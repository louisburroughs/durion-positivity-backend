package com.positivity.supplier.internal.pricecatalog.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * How fresh one vendor profile's price catalog is (#1637 decision 3).
 *
 * <p>Two of these facts are deliberately kept apart and must never be conflated:
 * {@code latestEffectiveDate} is <em>vendor document metadata</em> — the newest catalog document
 * date the vendor itself stated on a completed import — while {@code lastFetchedAt} is
 * <em>platform retrieval time</em> — when this deployment last called the vendor, successful or
 * not. A vendor that stamps its catalog monthly can be perfectly fresh by the first measure and a
 * feed can still be broken by the second.
 *
 * <p>{@code stale} is computed against the backend-owned {@code stalenessThreshold} and returned
 * alongside it, so every client draws the same conclusion from the same rule instead of each
 * frontend inventing its own constant. The threshold is a policy about catalog currency and is
 * unrelated to any request cache TTL.
 *
 * @param vendorProfileId          profile this freshness read describes
 * @param latestEffectiveDate      newest vendor-stated catalog document date over completed
 *                                 imports; vendor metadata, null when no completed import stated one
 * @param lastFetchedAt            when the vendor was last called, over every run including failed
 *                                 and empty ones; null when never fetched
 * @param lastCompletedAt          when staging last committed; null when no run ever completed
 * @param unresolvedUnmatchedCount open quarantine lines still awaiting a catalog fix
 * @param stalenessThreshold       the configured threshold, ISO-8601 duration (e.g. {@code P7D})
 * @param stale                    true when {@code lastCompletedAt} is older than the threshold, or
 *                                 no import ever completed; judged from the last successful import
 *                                 because staleness is about price-data currency, and a fetch
 *                                 attempt that stored nothing refreshed no prices
 * @param bindings                 the profile's PRICE_CATALOG bindings with their schedule and
 *                                 lease state; empty when none is configured
 */
@Schema(
        description = "Freshness of one vendor profile's price catalog: what the vendor last stated"
                + " (latestEffectiveDate, vendor document metadata) versus when this platform last retrieved it"
                + " (lastFetchedAt), judged against the backend-owned staleness threshold.")
public record PriceCatalogFreshnessView(
        @Schema(
                description = "Vendor profile this freshness read describes.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID vendorProfileId,

        @Schema(
                description = "Newest vendor-stated catalog document date over completed imports. Vendor"
                        + " document metadata, NOT retrieval time; null when no completed import stated one.",
                example = "2026-08-13")
        @Nullable
        LocalDate latestEffectiveDate,

        @Schema(
                description = "When the vendor was last called, over every run including failed and empty"
                        + " ones. Platform retrieval time, NOT vendor metadata; null when never fetched.")
        @Nullable
        Instant lastFetchedAt,

        @Schema(description = "When staging last committed; null when no run ever completed.") @Nullable
        Instant lastCompletedAt,

        @Schema(description = "Open quarantine lines still awaiting a catalog fix.", example = "42")
        long unresolvedUnmatchedCount,

        @Schema(
                description = "The backend-configured staleness threshold, as an ISO-8601 duration. Every"
                        + " client judges freshness by this same value.",
                example = "P7D")
        @NonNull
        String stalenessThreshold,

        @Schema(
                description = "True when lastCompletedAt is older than the threshold, or no import ever"
                        + " completed. Judged from the last successful import: a fetch attempt that stored"
                        + " nothing refreshed no prices.")
        boolean stale,

        @Schema(
                description = "The profile's PRICE_CATALOG bindings with their schedule and lease state;"
                        + " empty when none is configured.")
        @NonNull
        List<PriceCatalogBindingFreshness> bindings) {

    // Left as IllegalArgumentException (#1694): this is a response view built server-side from
    // internal freshness state, never from client input. A violation here is this module's own
    // defect, so it belongs on the platform 500 fallback, not a client 4xx.
    public PriceCatalogFreshnessView {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(stalenessThreshold, "stalenessThreshold must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        if (unresolvedUnmatchedCount < 0) {
            throw new IllegalArgumentException("unresolvedUnmatchedCount must be >= 0");
        }
        bindings = List.copyOf(bindings);
    }
}
