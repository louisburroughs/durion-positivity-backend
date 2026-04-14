package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PeopleAvailabilityResponse {
    Instant asOf;
    String location;
    List<PersonAvailability> people;

    @Value
    @Builder
    @Jacksonized
    public static class PersonAvailability {
        String personId;
        String firstName;
        String lastName;
        String currentStatus;
        String currentLocationId;
        List<String> certifications;
        ClockInfo clock;
        BreakInfo breakInfo;
        List<PtoBlock> pto;
        List<ScheduleSlot> scheduledAvailability;
    }

    @Value
    @Builder
    @Jacksonized
    public static class ClockInfo {
        Instant clockInTime;
        Instant clockOutTime;
    }

    @Value
    @Builder
    @Jacksonized
    public static class BreakInfo {
        boolean onBreak;
        Instant expectedReturn;
    }

    @Value
    @Builder
    @Jacksonized
    public static class PtoBlock {
        String ptoId;
        Instant start;
        Instant end;
        String ptoType;
    }

    @Value
    @Builder
    @Jacksonized
    public static class ScheduleSlot {
        Instant start;
        Instant end;
        String slotType;
    }
}
