package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

/**
 * Response DTO for successful appointment creation (HTTP 201).
 * Includes appointment details, facility timezone for display, and optional
 * notification outcome.
 * Per DECISION-SHOPMGMT-015: timestamps include facility timezone offset for
 * scheduledStartDateTime/EndTime;
 * createdAt/lastUpdatedAt are in UTC (Z).
 */
@Data
@Schema(description = "Response describing a created or retrieved appointment")
public class AppointmentResponse {

    @Schema(
            description = "Unique appointment identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID appointmentId;

    @Schema(description = "Current appointment status", example = "SCHEDULED", requiredMode = REQUIRED)
    private String status;

    @Schema(
            description = "Facility/location identifier of the appointment",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Resource (bay or mobile unit) reserved, if any",
            example = "BAY-04",
            requiredMode = NOT_REQUIRED)
    private String resourceId;

    @Schema(
            description = "CRM customer identifier the appointment is booked for",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID crmCustomerId;

    @Schema(
            description = "CRM vehicle identifier the appointment services",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID crmVehicleId;

    @Schema(
            description = "Appointment start instant in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant startAt;

    @Schema(
            description = "Appointment end instant in UTC (ISO-8601)",
            example = "2026-06-18T10:00:00Z",
            requiredMode = REQUIRED)
    private Instant endAt;

    @Schema(
            description = "Timestamp the appointment was created in UTC (ISO-8601)",
            example = "2026-06-17T15:30:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "When work actually began, resolved from the linked workorder's actual-time "
                    + "block through WorkOrderAppointmentMapping (issue #2021). Null when the appointment "
                    + "has no linked workorder, the link has not replicated yet, or work has not started. "
                    + "startAt above stays the planned window regardless.",
            example = "2026-06-18T08:05:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant actualStartAt;

    @Schema(
            description = "When work actually finished, resolved the same way as actualStartAt (#2021). "
                    + "Null while the linked workorder is still open, or when there is no link. endAt above "
                    + "stays the planned window regardless.",
            example = "2026-06-18T10:40:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant actualEndAt;

    @Schema(
            description = "The workorder owner's projection of when a still-running job will finish "
                    + "(#2021). Null in every response today by design: a projected finish needs estimated "
                    + "remaining labour (ADR-0058/ADR-0059, both PROPOSED, not accepted) and this field is "
                    + "never synthesised from the current time. A caller states \"N minutes over planned\" "
                    + "from actualStartAt, endAt and status instead of waiting on this field.",
            example = "null",
            requiredMode = NOT_REQUIRED)
    private Instant expectedEndAt;

    @Schema(
            description = "Cancellation reason code when the appointment has been cancelled",
            example = "CUSTOMER_REQUEST",
            requiredMode = NOT_REQUIRED)
    private String cancellationReason;

    @Schema(
            description = "Free-text cancellation notes when the appointment has been cancelled",
            example = "Customer rescheduled to next week",
            requiredMode = NOT_REQUIRED)
    private String cancellationNotes;

    @Schema(
            description = "Service request identifiers included in this appointment",
            example = "[\"01960003-0000-7000-8000-000000000004\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> serviceRequestIds;

    @Schema(description = "Snapshot of customer attributes captured at booking time", requiredMode = NOT_REQUIRED)
    private Map<String, Object> customerSnapshot;

    @Schema(description = "Snapshot of vehicle attributes captured at booking time", requiredMode = NOT_REQUIRED)
    private Map<String, Object> vehicleSnapshot;
}
