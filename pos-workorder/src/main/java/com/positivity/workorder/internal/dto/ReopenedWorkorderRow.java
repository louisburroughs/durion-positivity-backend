package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One reopen event (Wave 2 E6, #1594): a workorder completed, then reopened, within
 * {@code withinDays} of that completion. Per-event, not pre-counted — a workorder reopened twice
 * inside the window produces two rows; the caller counts per technician.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A single work-order reopen event within withinDays of its completion")
public class ReopenedWorkorderRow {

    @Schema(
            description = "The technician who completed the work order (attribution rule: the actor recorded on the "
                    + "completing state transition, resolved to a stable person id). Rows where that "
                    + "actor cannot be resolved to a technician are excluded rather than guessed — see the "
                    + "endpoint description.")
    private UUID technicianId;

    @Schema(description = "The work order that was reopened")
    private UUID woId;

    @Schema(description = "When the work order was completed (the event this reopen is anchored to)")
    private Instant completedAt;

    @Schema(description = "When this reopen occurred")
    private Instant reopenedAt;
}
