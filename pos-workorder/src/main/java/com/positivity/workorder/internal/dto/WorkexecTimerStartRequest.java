package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Start timer request for workexec time entries")
public class WorkexecTimerStartRequest {

    @NotNull
    @JsonProperty("workorderId")
    private UUID workorderId;

    @JsonProperty("workorderItemId")
    private UUID workorderItemId;

    @JsonProperty("laborCode")
    private String laborCode;
}
