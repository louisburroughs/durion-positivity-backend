package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Advances or annotates a part return (PRD §3.8). All fields optional: omit {@code status} to
 * update details (RMA number, tracking, hold note) without a lifecycle move. Legal transitions —
 * {@code AWAITING_PART → ON_HOLD → SHIPPED → RECEIVED_BY_VENDOR}, or to
 * {@code SCRAPPED}/{@code CLOSED} per disposition — are enforced by the service state machine.
 */
@Schema(description = "Part return (RMA) lifecycle update; all fields optional")
public record PartReturnUpdateRequest(
        @Schema(description = "Target status; omit for a detail-only update")
        PartReturnStatus status,

        @Schema(description = "Revised disposition for the defective part")
        PartReturnDisposition disposition,

        @Schema(description = "Shipping carrier") @Size(max = 100)
        String carrier,

        @Schema(description = "Carrier tracking number") @Size(max = 100)
        String trackingNumber,

        @Schema(description = "When the part shipped; defaults to now when entering SHIPPED")
        Instant shippedAt,

        @Schema(description = "Vendor-issued RMA number") @Size(max = 100)
        String rmaNumber,

        @Schema(description = "Where the part sits on the physical hold shelf") @Size(max = 10_000)
        String holdLocationNote) {}
