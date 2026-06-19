package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to complete a pick task")
public class CompletePickTaskRequest {

    @Schema(
            description = "Optional reason for completing the pick task",
            example = "All lines picked",
            requiredMode = NOT_REQUIRED)
    private String reason;
}
