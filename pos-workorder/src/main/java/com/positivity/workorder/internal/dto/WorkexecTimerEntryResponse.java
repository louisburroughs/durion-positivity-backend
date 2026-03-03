package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Timer entry response for workexec timer APIs")
public class WorkexecTimerEntryResponse {

    @JsonProperty("timeEntryId")
    @Schema(description = "Timer entry identifier", example = "550e8400-e29b-41d4-a716-446655440600")
    private UUID timeEntryId;

    @JsonProperty("mechanicId")
    @Schema(description = "Mechanic identifier", example = "550e8400-e29b-41d4-a716-446655440120")
    private UUID mechanicId;

    @JsonProperty("workorderId")
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @JsonProperty("workorderItemId")
    @Schema(description = "Optional workorder item identifier", example = "550e8400-e29b-41d4-a716-446655440010")
    private UUID workorderItemId;

    @JsonProperty("laborCode")
    @Schema(description = "Labor code", example = "DIAG")
    private String laborCode;

    @JsonProperty("startTime")
    @Schema(description = "Timer start timestamp")
    private LocalDateTime startTime;

    @JsonProperty("endTime")
    @Schema(description = "Timer stop timestamp")
    private LocalDateTime endTime;

    @JsonProperty("durationInSeconds")
    @Schema(description = "Computed duration in seconds", example = "3600")
    private Long durationInSeconds;

    @JsonProperty("status")
    @Schema(description = "Timer status", example = "STOPPED")
    private String status;
}
