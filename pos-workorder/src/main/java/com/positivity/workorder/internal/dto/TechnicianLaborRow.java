package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One technician's labor summary row (Wave 2 E5, #1593). See the endpoint description for the
 * per-column window semantics — {@code completedWoCount}, {@code billedHours} and
 * {@code laborRevenue} are each anchored to a different event (workorder completion, labor log
 * time, and invoice-of-a-completed-workorder respectively) and can disagree at month boundaries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One technician's labor summary for the window")
public class TechnicianLaborRow {

    @Schema(description = "Stable technician (person) id")
    private UUID technicianId;

    @Nullable
    @Schema(description = "Technician display name, resolved server-side; null when the person replica has no record")
    private String name;

    @Schema(
            description = "Work orders this technician completed in the window (attributed to the actor on the "
                    + "completing state transition; window = completion date, not labor log time)")
    private int completedWoCount;

    @Schema(
            description = "Sum of WorkorderLaborEntry.hoursWorked for this technician's stopped labor entries whose "
                    + "startTime falls in the window (window = labor log time, not workorder completion date)")
    private BigDecimal billedHours;

    @Schema(
            description = "Sum of ext_invoice.laborTotal across invoices attributable to this technician's "
                    + "completedWoCount work orders (window = the same completion date as completedWoCount). "
                    + "An invoice whose laborTotal is null (source event carried no line detail) is excluded "
                    + "from this sum rather than treated as zero.")
    private BigDecimal laborRevenue;
}
