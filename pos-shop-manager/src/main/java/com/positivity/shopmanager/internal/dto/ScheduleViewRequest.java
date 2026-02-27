package com.positivity.shopmanager.internal.dto;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
public class ScheduleViewRequest {
    private UUID locationId;
    private LocalDate date;
    private String resourceType;
    private String resourceId;
    private boolean includeAvailabilityOverlay;
    private String range;
}
