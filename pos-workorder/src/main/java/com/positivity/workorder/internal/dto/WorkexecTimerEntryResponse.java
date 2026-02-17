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
    private UUID timeEntryId;

    @JsonProperty("mechanicId")
    private UUID mechanicId;

    @JsonProperty("workorderId")
    private UUID workorderId;

    @JsonProperty("workorderItemId")
    private UUID workorderItemId;

    @JsonProperty("laborCode")
    private String laborCode;

    @JsonProperty("startTime")
    private LocalDateTime startTime;

    @JsonProperty("endTime")
    private LocalDateTime endTime;

    @JsonProperty("durationInSeconds")
    private Long durationInSeconds;

    @JsonProperty("status")
    private String status;
}
