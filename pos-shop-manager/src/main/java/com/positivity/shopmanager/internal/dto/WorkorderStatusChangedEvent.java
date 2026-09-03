package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * In-process notification that a replicated workorder changed status (#1658).
 *
 * <p>Raised by {@link com.positivity.shopmanager.internal.service.WorkorderEventsListener} after it
 * has applied a {@code workorder.workorder.updated} fact whose status differs from the one this
 * module already held, and consumed by
 * {@link com.positivity.shopmanager.internal.config.WorkorderStatusChangedEventListener} to keep
 * the linked appointment's status in step. Firing only on an actual transition is what keeps the
 * appointment status timeline free of a duplicate entry per unrelated workorder edit.
 *
 * <p>The payload was widened from {@code (eventId, workorderId, newStatus, eventTimestamp,
 * correlationId)} to carry the assignment context the shop dashboard reads: the unit the job
 * occupies and what kind of unit it is, every assigned technician, the vehicle, the promise time
 * and the workorder number. All of it is a verbatim copy of pos-workorder's facts — this module is
 * a read-only consumer of them and never the system of record (ADR-0044 R6).
 *
 * <p>{@code mechanicIds} is a list, not a scalar: a workorder may carry more than one technician,
 * and the first element is not privileged. {@code promisedAt} is always null today because the
 * owner has no promise-time field; the slot exists so the contract does not have to change when it
 * grows one.
 */
@Schema(description = "Event published when a workorder transitions to a new status")
public record WorkorderStatusChangedEvent(
        @Schema(
                description = "Unique event identifier",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        @NonNull
        UUID eventId,

        @Schema(
                description = "Workorder identifier whose status changed",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = REQUIRED)
        @NonNull
        UUID workorderId,

        @Schema(description = "New workorder status", example = "WORK_IN_PROGRESS", requiredMode = REQUIRED) @NonNull
        String newStatus,

        @Schema(
                description = "Instant the status change occurred in UTC (ISO-8601)",
                example = "2026-06-18T08:00:00Z",
                requiredMode = REQUIRED)
        @NonNull
        Instant eventTimestamp,

        @Schema(
                description = "Correlation identifier propagated from the originating request",
                example = "01960003-0000-7000-8000-0000000000ff",
                requiredMode = NOT_REQUIRED)
        @Nullable
        UUID correlationId,

        @Schema(description = "Human-readable workorder number", example = "WO-2026-1001", requiredMode = NOT_REQUIRED)
        @Nullable
        String workorderNumber,

        @Schema(
                description = "Location the work is performed at",
                example = "018e1c9f-6b5a-7890-abcd-1234567890ab",
                requiredMode = NOT_REQUIRED)
        @Nullable
        UUID locationId,

        @Schema(
                description = "Bay or mobile unit the workorder occupies; null when unassigned",
                example = "01960005-0000-7000-8000-0000000000b1",
                requiredMode = NOT_REQUIRED)
        @Nullable
        UUID resourceId,

        @Schema(
                description = "Whether resourceId names a bay or a mobile unit",
                example = "BAY",
                requiredMode = NOT_REQUIRED)
        @Nullable
        ShopDashboardUnitType resourceType,

        @Schema(description = "Every technician assigned to the workorder", requiredMode = NOT_REQUIRED) @NonNull
        List<UUID> mechanicIds,

        @Schema(
                description = "Serviced vehicle",
                example = "01960004-0000-7000-8000-0000000000a1",
                requiredMode = NOT_REQUIRED)
        @Nullable
        UUID vehicleId,

        @Schema(
                description = "Time the vehicle is promised back to the customer",
                example = "2026-09-03T17:00:00Z",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Instant promisedAt,

        @Schema(description = "Day the work is scheduled for", example = "2026-09-03", requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate scheduledDate) {

    public WorkorderStatusChangedEvent {
        mechanicIds = mechanicIds == null ? List.of() : List.copyOf(mechanicIds);
    }

    /** Pre-#1658 arity: status transition only, with no assignment context. */
    public WorkorderStatusChangedEvent(
            @NonNull UUID eventId,
            @NonNull UUID workorderId,
            @NonNull String newStatus,
            @NonNull Instant eventTimestamp,
            @Nullable UUID correlationId) {
        this(
                eventId,
                workorderId,
                newStatus,
                eventTimestamp,
                correlationId,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null);
    }
}
