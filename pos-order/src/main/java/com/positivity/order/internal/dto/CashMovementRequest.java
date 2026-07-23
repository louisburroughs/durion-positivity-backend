package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Request payload for recording a drawer cash movement")
public class CashMovementRequest {

    @Schema(
            description = "Movement direction",
            example = "PAID_OUT",
            allowableValues = {"PAID_IN", "PAID_OUT"},
            requiredMode = REQUIRED)
    @NotBlank
    private String movementType;

    @Schema(description = "Positive cash amount moved", example = "40.00", requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal amount;

    @Schema(description = "Reason for the movement", example = "petty cash to office", requiredMode = REQUIRED)
    @NotBlank
    private String reason;

    @Schema(
            description = "Clerk recording the movement",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = REQUIRED)
    @NotBlank
    private String clerkId;
}
