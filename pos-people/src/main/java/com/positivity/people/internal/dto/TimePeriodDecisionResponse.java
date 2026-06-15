package com.positivity.people.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TimePeriodDecisionResponse {

    private UUID personId;
    private UUID timePeriodId;
    private int processedCount;
    private String status;
}
