package com.positivity.workorder.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WorkorderSummary {
    UUID workorderId;
    String workorderNumber;
    String status;
    String customerName;
    String vehicleDescription;
    LocalDate scheduledDate;
    String assignedMechanicId;
    String assignedBayId;
    BigDecimal estimatedLaborHours;
}
