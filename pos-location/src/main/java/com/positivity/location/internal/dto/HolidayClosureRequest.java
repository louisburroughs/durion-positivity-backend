package com.positivity.location.internal.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holiday-closure payload entry for a location.
 *
 * Issue: CAP-136 #78
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HolidayClosureRequest {
    private LocalDate date;
    private String reason;
}
