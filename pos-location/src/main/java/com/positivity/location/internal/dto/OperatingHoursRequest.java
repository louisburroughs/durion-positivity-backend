package com.positivity.location.internal.dto;

import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Operating-hours payload entry for a location.
 *
 * Issue: CAP-136 #78
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatingHoursRequest {
    private String dayOfWeek;
    private LocalTime openTime;
    private LocalTime closeTime;
}
