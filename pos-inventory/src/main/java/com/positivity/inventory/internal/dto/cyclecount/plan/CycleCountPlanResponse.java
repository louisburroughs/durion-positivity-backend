package com.positivity.inventory.internal.dto.cyclecount.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountPlanResponse {

    private UUID planId;
    private UUID locationId;
    private List<UUID> zoneIds;
    private String planName;
    private LocalDate scheduledDate;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
