package com.positivity.location.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial update request for location mutation endpoints.
 *
 * Issue: CAP-136 #78
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationPatchRequest {
    private String name;
    private String status;
    private String timezone;
    private List<OperatingHoursRequest> operatingHours;
    private List<HolidayClosureRequest> holidayClosures;
    private Integer checkInBufferMinutes;
    private Integer cleanupBufferMinutes;
}
