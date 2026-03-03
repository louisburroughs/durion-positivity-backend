package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for rejecting a time entry")
public class RejectTimeEntryRequest {

    @NotBlank
    @Schema(description = "Mandatory reason for rejection")
    private String rejectionReason;
}
