package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class ScheduleViewResponse {
    private UUID locationId;
    private LocalDate date;
    private Instant viewGeneratedAt;
    private Instant dayStartAt;
    private Instant dayEndAt;
    private String availabilityOverlayStatus;
    private List<String> warnings;
    private List<ScheduleResourceView> resources;

    @Data
    public static class ScheduleResourceView {
        private String resourceId;
        private String resourceType;
        private String resourceName;
        private List<ScheduleEventView> events;
    }

    @Data
    public static class ScheduleEventView {
        private String eventId;
        private String eventType;
        private String subType;
        private Instant startTime;
        private Instant endTime;
        private String title;
        private boolean hasConflict;
        private String severity;
        private ConflictDetails conflictDetails;
    }

    @Data
    public static class ConflictDetails {
        private List<String> conflictingEventIds;
    }
}
