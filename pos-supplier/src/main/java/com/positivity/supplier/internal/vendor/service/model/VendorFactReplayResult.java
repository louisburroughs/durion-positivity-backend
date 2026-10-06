package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of one page of a vendor-fact replay (#2516, ADR-0044 §4; the pos-customer
 * {@code PartyFactReplayResultDto} shape).
 *
 * @param emitted facts queued by this call
 * @param nextAfterVendorId cursor to pass as {@code afterVendorId} next; {@code null} at the end
 * @param complete true when no further pages remain
 * @param startedAt when this page began
 */
@Schema(description = "Outcome of one page of a vendor-fact replay.")
public record VendorFactReplayResult(
        @Schema(description = "Facts queued by this call.", example = "200")
        int emitted,

        @Schema(
                description = "Cursor for the next page; null at the end.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @Nullable
        UUID nextAfterVendorId,

        @Schema(description = "True when no further pages remain.", example = "true")
        boolean complete,

        @Schema(description = "When this page began.") @NonNull
        Instant startedAt) {}
