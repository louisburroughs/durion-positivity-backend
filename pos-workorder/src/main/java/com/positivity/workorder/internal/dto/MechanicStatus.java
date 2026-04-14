package com.positivity.workorder.internal.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

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
