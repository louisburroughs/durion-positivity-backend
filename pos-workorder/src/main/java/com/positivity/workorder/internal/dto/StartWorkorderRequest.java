package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Optional request payload used when starting a workorder")
public class StartWorkorderRequest {
    @Schema(
            description = "Deprecated. Actor identity is resolved from authenticated security context.",
            deprecated = true)
    private UUID userId;

    @Schema(
            description = "Optional reason associated with start transition",
            example = "Vehicle pulled into service bay")
    private String reason;
}
