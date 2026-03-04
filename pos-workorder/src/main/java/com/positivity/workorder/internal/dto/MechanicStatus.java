package com.positivity.workorder.internal.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class MechanicStatus {
    String personId;
    String firstName;
    String lastName;
    String currentStatus;
    String assignedWorkorderId;
    boolean onBreak;
    Instant breakExpectedReturn;
    List<PtoEntry> ptoEntries;
}