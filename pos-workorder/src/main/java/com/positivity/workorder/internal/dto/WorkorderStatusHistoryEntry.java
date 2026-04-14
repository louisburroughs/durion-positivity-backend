package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.WorkorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A single entry in the workorder status change history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single entry in the workorder status history")
public class WorkorderStatusHistoryEntry {

    @Schema(description = "Status reached at this point in history")
    private WorkorderStatus status;

    @Schema(description = "Timestamp when the status change occurred")
    private Instant changedAt;

    @Schema(description = "Identity of the user who changed the status")
    private String changedBy;

    @Nullable
    @Schema(description = "Optional reason provided for the status change")
    private String reason;
}
