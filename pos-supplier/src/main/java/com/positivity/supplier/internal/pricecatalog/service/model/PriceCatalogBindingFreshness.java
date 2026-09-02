package com.positivity.supplier.internal.pricecatalog.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One PRICE_CATALOG binding's schedule and lease state within a freshness read (#1637 decision 3).
 *
 * <p>The lease fields describe the <em>scheduler's</em> bookkeeping for the binding: when its last
 * scheduled run started, how it ended, and the checkpoint the coordinator committed. They are null
 * when no lease row exists yet — a binding that has never been claimed by a scheduled run — and
 * {@code checkpointAt} additionally stays null for full-snapshot protocols, which advance no
 * checkpoint (every current PRICAT protocol; see the scheduler's "why the lease, but not the
 * checkpoint").
 *
 * @param bindingId        the endpoint binding
 * @param scheduleCron     the binding's schedule; null when it only runs on demand
 * @param enabled          whether the binding is enabled
 * @param checkpointAt     end of the last window the schedule coordinator checkpointed; null for
 *                         full-snapshot protocols and for bindings never claimed
 * @param lastRunOutcome   how the last scheduled run ended (an import status name, or FAILED);
 *                         null when never run
 * @param lastRunStartedAt when the last scheduled run started; null when never run
 */
@Schema(description = "One PRICE_CATALOG binding's schedule and scheduler-lease state.")
public record PriceCatalogBindingFreshness(
        @Schema(description = "The endpoint binding.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e") @NonNull
        UUID bindingId,

        @Schema(
                description = "The binding's schedule cron; null when the feed only runs on demand.",
                example = "0 0 3 * * *")
        @Nullable
        String scheduleCron,

        @Schema(description = "Whether the binding is enabled.")
        boolean enabled,

        @Schema(
                description = "End of the last window the schedule coordinator checkpointed. Null for"
                        + " full-snapshot protocols — every current PRICAT protocol — and for bindings a"
                        + " scheduled run has never claimed.")
        @Nullable
        Instant checkpointAt,

        @Schema(
                description = "How the last scheduled run ended; null when a scheduled run never ran.",
                example = "COMPLETED")
        @Nullable
        String lastRunOutcome,

        @Schema(description = "When the last scheduled run started; null when a scheduled run never ran.") @Nullable
        Instant lastRunStartedAt) {

    public PriceCatalogBindingFreshness {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
    }
}
