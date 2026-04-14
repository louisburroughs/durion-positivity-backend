package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.WorkorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full detail view of a single workorder's WIP status — returned from the
 * single-workorder WIP endpoint. Contains all fields from
 * {@link WorkorderStatusView} plus extended diagnostic data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Full WIP detail for a single workorder")
public class WorkorderStatusDetail {

    // ---- fields mirrored from WorkorderStatusView ----

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

    // ---- extended detail fields ----

    @Schema(description = "Ordered history of all status changes for this workorder")
    private List<WorkorderStatusHistoryEntry> statusHistory;

    @Nullable
    @Schema(description = "Customer contact phone number (null if not available)")
    private String phoneNumber;

    @Nullable
    @Schema(description = "Vehicle VIN (null if not recorded)")
    private String vehicleVin;

    @Schema(description = "Summary of the service being performed")
    private String serviceDescription;

    @Nullable
    @Schema(description = "Internal shop notes (null if none)")
    private String internalNotes;

    @Schema(description = "Part numbers or descriptions that are blocking progress")
    private List<String> partsBlocking;
}
