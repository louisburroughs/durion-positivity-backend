package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ApprovalStatus;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TimePeriodApprovalDto {

    private UUID personId;
    private UUID timePeriodId;
    private int totalCount;
    private int pendingCount;
    private int approvedCount;
    private int rejectedCount;
    private ApprovalStatus overallStatus;
}
