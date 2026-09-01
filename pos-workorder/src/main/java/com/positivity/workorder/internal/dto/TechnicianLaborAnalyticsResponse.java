package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response envelope for {@code GET /v1/workorders/analytics/technician-labor} (Wave 2 E5, #1593). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Per-technician labor summary rows for the window, with truncation signalling")
public class TechnicianLaborAnalyticsResponse {

    @Schema(description = "One row per technician, ordered by billedHours descending, capped at `limit`")
    private List<TechnicianLaborRow> rows;

    @Schema(
            description =
                    "True when more technicians had matching activity than `limit` allowed and the result was capped")
    private boolean truncated;

    @Schema(description = "The limit applied to this response")
    private int limit;
}
