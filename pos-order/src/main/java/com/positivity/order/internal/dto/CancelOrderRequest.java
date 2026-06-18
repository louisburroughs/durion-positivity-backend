package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for cancelling an order")
public class CancelOrderRequest {

    @Schema(
            description = "Reason explaining why the order is being cancelled",
            example = "Customer changed their mind",
            requiredMode = REQUIRED)
    @NotBlank(message = "Cancellation reason must not be blank")
    private String cancellationReason;

    @Schema(
            description = "Identifier of the associated workorder, when applicable",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID workOrderId;

    @Schema(
            description = "Identifier of the associated payment, when applicable",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID paymentId;

    @Schema(
            description = "Optional idempotency key for duplicate cancellation prevention",
            example = "cancel-req-abc123",
            requiredMode = NOT_REQUIRED)
    private String idempotencyKey;
}
