package com.positivity.order.internal.dto.purchaseorder;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing the vendor said about a purchase order, as the timeline read returns it (issue #1638).
 *
 * <p>Both clocks travel together: {@code observedAt} is the vendor's statement of when this
 * happened and orders the timeline, {@code recordedAt} is when this platform heard it. Keeping the
 * two apart is what makes a late arrival visible instead of silently reshuffled history.
 */
@Schema(
        name = "PurchaseOrderTransmissionEvent",
        description = "One entry on a purchase order's vendor transmission timeline: a confirmation, rejection,"
                + " status observation or review escalation. Entries are ordered by the vendor's clock"
                + " (observedAt), with ties broken by platform receipt time (recordedAt) and then by event id,"
                + " so the sequence is stable across reads.")
public record PurchaseOrderTransmissionEventResponse(
        @Schema(description = "Identifier of this timeline entry (UUIDv7).")
        UUID transmissionEventId,

        @Schema(description = "The transmission intent in pos-supplier this observation belongs to.") @Nullable
        UUID transmissionIntentId,

        @Schema(
                description = "CONFIRMED, REJECTED, STATUS_CHANGED or REVIEW_REQUIRED. The last records that"
                        + " the transmission stopped and needs a person, not a vendor answer.")
        String eventType,

        @Schema(
                description = "The vendor-reported status on a status change, or the rejection reason code on a"
                        + " rejection; null on a confirmation or review escalation.")
        @Nullable
        String status,

        @Schema(description = "The vendor-side document id the observation refers to.") @Nullable
        String vendorDocumentId,

        @Schema(description = "The order number the vendor assigned, once it stated one.") @Nullable
        String supplierOrderNumber,

        @Schema(description = "The vendor's own wording — a rejection reason or review detail — when it gave one.")
        @Nullable
        String vendorReason,

        @Schema(description = "Earliest despatch date the vendor stated across the order's lines, when stated.")
        @Nullable
        LocalDate despatchDate,

        @Schema(description = "Earliest delivery date the vendor scheduled across the order's lines, when stated.")
        @Nullable
        LocalDate estimatedDeliveryDate,

        @Schema(description = "The vendor's clock: when it says this happened. Orders the timeline.")
        Instant observedAt,

        @Schema(
                description = "This platform's clock: when the observation was heard. Breaks observedAt ties and"
                        + " shows how late an observation arrived.")
        Instant recordedAt) {}
