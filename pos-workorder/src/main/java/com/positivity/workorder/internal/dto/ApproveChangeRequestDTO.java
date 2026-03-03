package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for approving a change request")
public class ApproveChangeRequestDTO {
    @Schema(description = "Deprecated. Actor identity is resolved from authenticated security context.", example = "550e8400-e29b-41d4-a716-446655440100", deprecated = true)
    private UUID approvedBy;

    @NotBlank(message = "approvalNote is required")
    @Schema(description = "Approval note captured as the decision artifact", example = "Approved after customer confirmation")
    private String approvalNote;
}
