package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response envelope for {@code GET /v1/workorders/analytics/reopened} (Wave 2 E6, #1594). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Per-event reopened-workorder rows within the window, with truncation signalling")
public class ReopenedWorkorderAnalyticsResponse {

    @Schema(description = "Reopen events, ordered oldest first, capped at `limit`")
    private List<ReopenedWorkorderRow> rows;

    @Schema(
            description =
                    "True when more matching reopen events existed than `limit` allowed and the result was capped")
    private boolean truncated;

    @Schema(description = "The limit applied to this response")
    private int limit;
}
