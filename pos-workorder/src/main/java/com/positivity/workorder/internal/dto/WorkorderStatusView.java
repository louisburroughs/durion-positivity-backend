package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.WorkorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Summary view of a workorder's WIP status — used in paginated list responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Summary view of a workorder's work-in-progress status")
public class WorkorderStatusView {

    @Schema(description = "Workorder unique identifier")
    private UUID workorderId;

    @Schema(description = "Current workorder status")
    private WorkorderStatus status;

    @Nullable
    @Schema(description = "ID of the technician currently assigned (null if unassigned)")
    private String assignedTechnicianId;

    @Schema(description = "Location ID the workorder belongs to")
    private String locationId;

    @Nullable
    @Schema(description = "Estimated time of completion (null if not set)")
    private Instant estimatedCompletionTime;

    @Schema(description = "Full name of the customer")
    private String customerName;

    @Schema(description = "Human-readable vehicle description (year/make/model/trim)")
    private String vehicleInfo;

    @Schema(description = "Timestamp of the last status update")
    private Instant lastUpdatedAt;
}
