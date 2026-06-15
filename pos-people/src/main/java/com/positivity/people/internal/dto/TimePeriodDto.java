package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.TimePeriodStatus;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TimePeriodDto {

    private UUID timePeriodId;
    private UUID tenantId;
    private LocalDate startDate;
    private LocalDate endDate;
    private TimePeriodStatus status;
}
