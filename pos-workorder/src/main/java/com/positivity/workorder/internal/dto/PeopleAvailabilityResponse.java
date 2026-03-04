package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PeopleAvailabilityResponse {
    Instant asOf;
    String location;
    List<PersonAvailability> people;

    @Value
    @Builder
    public static class PersonAvailability {
        String personId;
        String firstName;
        String lastName;
        String currentStatus;
        ClockInfo clock;
        BreakInfo breakInfo;
        List<PtoBlock> pto;
        List<ScheduleSlot> scheduledAvailability;
    }

    @Value
    @Builder
    public static class ClockInfo {
        Instant clockInTime;
        Instant clockOutTime;
    }

    @Value
    @Builder
    public static class BreakInfo {
        boolean onBreak;
        Instant expectedReturn;
    }

    @Value
    @Builder
    public static class PtoBlock {
        String ptoId;
        Instant start;
        Instant end;
        String ptoType;
    }

    @Value
    @Builder
    public static class ScheduleSlot {
        Instant start;
        Instant end;
        String slotType;
    }
}
