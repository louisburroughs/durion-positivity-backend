package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Schedule view for a location and date, grouped by resource with their events")
public class ScheduleViewResponse {

    @Schema(
            description = "Location identifier the schedule belongs to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Calendar date of the schedule view (ISO-8601)",
            example = "2026-06-18",
            requiredMode = REQUIRED)
    private LocalDate date;

    @Schema(
            description = "Instant the view was generated in UTC (ISO-8601)",
            example = "2026-06-18T07:55:00Z",
            requiredMode = REQUIRED)
    private Instant viewGeneratedAt;

    @Schema(
            description = "Start of the displayed day window in UTC (ISO-8601)",
            example = "2026-06-18T12:00:00Z",
            requiredMode = REQUIRED)
    private Instant dayStartAt;

    @Schema(
            description = "End of the displayed day window in UTC (ISO-8601)",
            example = "2026-06-18T21:00:00Z",
            requiredMode = REQUIRED)
    private Instant dayEndAt;

    @Schema(
            description = "Status of the availability overlay (e.g. AVAILABLE, UNAVAILABLE, SKIPPED)",
            example = "AVAILABLE",
            requiredMode = NOT_REQUIRED)
    private String availabilityOverlayStatus;

    @Schema(
            description = "Non-fatal warnings generated while assembling the view",
            example = "[\"HR availability data was unavailable\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> warnings;

    @Schema(description = "Resources and their scheduled events", requiredMode = REQUIRED)
    private List<ScheduleResourceView> resources;

    @Data
    @Schema(description = "A single resource lane within the schedule view")
    public static class ScheduleResourceView {

        @Schema(
                description = "Resource identifier",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = REQUIRED)
        private String resourceId;

        @Schema(description = "Resource type (e.g. MECHANIC or BAY)", example = "MECHANIC", requiredMode = REQUIRED)
        private String resourceType;

        @Schema(description = "Display name of the resource", example = "Jane Doe", requiredMode = NOT_REQUIRED)
        private String resourceName;

        @Schema(description = "Events scheduled for this resource", requiredMode = REQUIRED)
        private List<ScheduleEventView> events;
    }

    @Data
    @Schema(description = "A single event displayed within a resource lane")
    public static class ScheduleEventView {

        @Schema(
                description = "Event identifier",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        private String eventId;

        @Schema(
                description = "Event type (e.g. APPOINTMENT, SHIFT, PTO)",
                example = "APPOINTMENT",
                requiredMode = REQUIRED)
        private String eventType;

        @Schema(description = "Event sub-type, when applicable", example = "DIAGNOSTIC", requiredMode = NOT_REQUIRED)
        private String subType;

        @Schema(
                description = "Event start instant in UTC (ISO-8601)",
                example = "2026-06-18T13:00:00Z",
                requiredMode = REQUIRED)
        private Instant startTime;

        @Schema(
                description = "Event end instant in UTC (ISO-8601)",
                example = "2026-06-18T15:00:00Z",
                requiredMode = REQUIRED)
        private Instant endTime;

        @Schema(description = "Event display title", example = "Oil change - Jane Doe", requiredMode = NOT_REQUIRED)
        private String title;

        @Schema(
                description = "Whether this event has a scheduling conflict",
                example = "false",
                requiredMode = REQUIRED)
        private boolean hasConflict;

        @Schema(
                description = "Conflict severity when hasConflict is true (HARD or SOFT)",
                example = "SOFT",
                requiredMode = NOT_REQUIRED)
        private String severity;

        @Schema(description = "Details of the conflict when present", requiredMode = NOT_REQUIRED)
        private ConflictDetails conflictDetails;
    }

    @Data
    @Schema(description = "Details about a conflict associated with a schedule event")
    public static class ConflictDetails {

        @Schema(
                description = "Identifiers of events that conflict with this event",
                example = "[\"01960003-0000-7000-8000-000000000002\"]",
                requiredMode = NOT_REQUIRED)
        private List<String> conflictingEventIds;
    }
}
