package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitTravelSegmentsRequest {
    @NotNull
    // reserved: future audit scope query filter; currently not consumed by
    // submitTravelSegments
    private LocalDate workDate;

    private String notes;
}
