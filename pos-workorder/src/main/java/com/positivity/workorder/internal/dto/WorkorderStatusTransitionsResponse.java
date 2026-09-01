package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response envelope for {@code GET /v1/workorders/status-transitions} (Wave 2 E7, #1595).
 *
 * <p>Wraps the reused {@link WorkorderStateTransitionResponse} row shape with the truncation
 * contract every Wave 2 analytics endpoint carries (plan §4.2, W1.3): a capped result is signalled
 * in the body rather than silently dropping rows, so a caller can tell a capped result from a
 * complete one without a second call.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Date-range (or single-workorder) status transition history, with truncation signalling")
public class WorkorderStatusTransitionsResponse {

    @Schema(description = "Matching transitions, oldest first, capped at `limit`")
    private List<WorkorderStateTransitionResponse> transitions;

    @Schema(description = "True when more matching transitions existed than `limit` allowed and the result was capped")
    private boolean truncated;

    @Schema(description = "The limit applied to this response")
    private int limit;
}
