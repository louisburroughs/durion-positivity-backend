package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ApprovalStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TimekeepingEntryDto {

    private UUID timekeepingEntryId;
    private UUID employeeId;
    private Instant sessionStartTime;
    private Instant sessionEndTime;
    private ApprovalStatus approvalStatus;
    private UUID associatedWorkOrderId;
}
