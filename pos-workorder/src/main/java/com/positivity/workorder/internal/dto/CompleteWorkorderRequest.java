package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to complete a workorder")
public class CompleteWorkorderRequest {
    @Schema(
            description = "Deprecated. Actor identity is resolved from authenticated security context.",
            deprecated = true)
    private UUID userId;

    @Schema(description = "Optional completion notes recorded for closeout", example = "Completed and verified")
    private String completionNotes;
}
