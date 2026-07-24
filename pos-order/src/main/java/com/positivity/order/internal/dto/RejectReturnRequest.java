package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request payload for rejecting a pending return")
public class RejectReturnRequest {

    @Schema(description = "Reason for the rejection", requiredMode = REQUIRED)
    @NotBlank
    private String reason;
}
