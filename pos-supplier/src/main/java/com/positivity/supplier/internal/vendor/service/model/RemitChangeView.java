package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A remit-to change request (#2516).
 *
 * @param changeId change identity (UUIDv7)
 * @param vendorId the vendor it changes
 * @param proposedRemitTo the proposed remit-to
 * @param reason why it was requested
 * @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED}
 * @param fromVersion the vendor's remit-to version when requested
 * @param toVersion the version the approval produced; {@code null} unless approved
 * @param requestedBy requester
 * @param requestedAt request time
 * @param decidedBy approver or rejecter; {@code null} while pending
 * @param decidedAt decision time; {@code null} while pending
 * @param decisionNote the approver's verification note or the rejection note
 */
@Schema(description = "A remit-to change request.")
public record RemitChangeView(
        @Schema(description = "Change identity (UUIDv7).", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c") @NonNull
        UUID changeId,

        @Schema(description = "Vendor it changes.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b") @NonNull
        UUID vendorId,

        @Schema(description = "Proposed remit-to.") @NonNull RemitToDto proposedRemitTo,

        @Schema(description = "Why it was requested.") @NonNull
        String reason,

        @Schema(description = "PENDING, APPROVED or REJECTED.", example = "PENDING") @NonNull
        RemitChangeStatus status,

        @Schema(description = "Vendor's remit-to version when requested.", example = "1")
        int fromVersion,

        @Schema(description = "Version the approval produced; null unless approved.", example = "2") @Nullable
        Integer toVersion,

        @Schema(description = "Requester.", example = "clerk.a") @NonNull
        String requestedBy,

        @Schema(description = "Request time.") @NonNull Instant requestedAt,

        @Schema(description = "Approver or rejecter; null while pending.", example = "controller.b") @Nullable
        String decidedBy,

        @Schema(description = "Decision time; null while pending.") @Nullable
        Instant decidedAt,

        @Schema(description = "Approver's verification note or the rejection note.") @Nullable
        String decisionNote) {}
