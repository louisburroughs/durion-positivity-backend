package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Start timer request for workexec time entries")
public class WorkexecTimerStartRequest {

    @NotNull
    @JsonProperty("workorderId")
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @JsonProperty("workorderItemId")
    @Schema(description = "Optional workorder line item identifier", example = "550e8400-e29b-41d4-a716-446655440010")
    private UUID workorderItemId;

    @JsonProperty("laborCode")
    @Schema(description = "Optional labor code associated with timer", example = "DIAG")
    private String laborCode;

    @JsonProperty("technicianId")
    @Schema(
            description = "Optional technician whose labor this timer tracks. Omit to use the workorder's "
                    + "current assignment, or yourself if the workorder is unassigned. Supplying a technician "
                    + "other than yourself or the current assignee requires the 'workorder:labor:add_on_behalf' "
                    + "authority and a reason.",
            example = "550e8400-e29b-41d4-a716-446655440099")
    private UUID technicianId;

    @JsonProperty("reason")
    @Schema(
            description = "Required when starting a timer on behalf of a technician other than yourself "
                    + "or the current assignee.",
            example = "Lead starting timer for helper technician")
    private String reason;
}
