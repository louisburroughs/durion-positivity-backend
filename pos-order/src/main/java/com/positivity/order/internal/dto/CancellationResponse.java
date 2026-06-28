package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Response returned after cancelling an order")
public class CancellationResponse {

    @Schema(
            description = "Identifier of the cancelled order",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String orderId;

    @Schema(
            description = "Resulting status of the order after cancellation",
            example = "CANCELLED",
            requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Human-readable message describing the cancellation outcome",
            example = "Order cancelled successfully",
            requiredMode = NOT_REQUIRED)
    private String message;

    @Schema(
            description = "Idempotency key associated with the cancellation request",
            example = "cancel-req-abc123",
            requiredMode = NOT_REQUIRED)
    private String cancellationIdempotencyKey;
}
