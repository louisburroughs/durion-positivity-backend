package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Availability snapshot for a set of people at a location")
public class PeopleAvailabilityResponse {

    @Schema(description = "Timestamp the availability was computed", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    Instant asOf;

    @Schema(description = "Location identifier the snapshot applies to", example = "550e8400-e29b-41d4-a716-446655440300", requiredMode = NOT_REQUIRED)
    String location;

    @Schema(description = "Availability records for each person", requiredMode = NOT_REQUIRED)
    List<PersonAvailability> people;

    @Value
    @Builder
    @Jacksonized
    @Schema(description = "Availability detail for a single person")
    public static class PersonAvailability {

        @Schema(description = "Person identifier", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
        String personId;

        @Schema(description = "Person first name", example = "Alex", requiredMode = NOT_REQUIRED)
        String firstName;

        @Schema(description = "Person last name", example = "Rivera", requiredMode = NOT_REQUIRED)
        String lastName;

        @Schema(description = "Current work status of the person", example = "AVAILABLE", requiredMode = NOT_REQUIRED)
        String currentStatus;

        @Schema(description = "Identifier of the person's current location", example = "550e8400-e29b-41d4-a716-446655440300", requiredMode = NOT_REQUIRED)
        String currentLocationId;

        @Schema(description = "Certifications held by the person", example = "[\"ASE_BRAKES\"]", requiredMode = NOT_REQUIRED)
        List<String> certifications;

        @Schema(description = "Clock in/out information", requiredMode = NOT_REQUIRED)
        ClockInfo clock;

        @Schema(description = "Break information", requiredMode = NOT_REQUIRED)
        BreakInfo breakInfo;

        @Schema(description = "Paid time-off blocks", requiredMode = NOT_REQUIRED)
        List<PtoBlock> pto;

        @Schema(description = "Scheduled availability slots", requiredMode = NOT_REQUIRED)
        List<ScheduleSlot> scheduledAvailability;
    }

    @Value
    @Builder
    @Jacksonized
    @Schema(description = "Clock in and clock out times for a person")
    public static class ClockInfo {

        @Schema(description = "Time the person clocked in", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant clockInTime;

        @Schema(description = "Time the person clocked out", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant clockOutTime;
    }

    @Value
    @Builder
    @Jacksonized
    @Schema(description = "Break status for a person")
    public static class BreakInfo {

        @Schema(description = "Whether the person is currently on break", example = "false", requiredMode = REQUIRED)
        boolean onBreak;

        @Schema(description = "Expected return time from break", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant expectedReturn;
    }

    @Value
    @Builder
    @Jacksonized
    @Schema(description = "A paid time-off block for a person")
    public static class PtoBlock {

        @Schema(description = "Paid time-off identifier", example = "01960003-0000-7000-8000-000000000004", requiredMode = REQUIRED)
        String ptoId;

        @Schema(description = "Start of the paid time-off block", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant start;

        @Schema(description = "End of the paid time-off block", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant end;

        @Schema(description = "Type of paid time-off", example = "VACATION", requiredMode = NOT_REQUIRED)
        String ptoType;
    }

    @Value
    @Builder
    @Jacksonized
    @Schema(description = "A scheduled availability slot for a person")
    public static class ScheduleSlot {

        @Schema(description = "Start of the scheduled slot", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant start;

        @Schema(description = "End of the scheduled slot", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        Instant end;

        @Schema(description = "Type of scheduled slot", example = "SHIFT", requiredMode = NOT_REQUIRED)
        String slotType;
    }
}
