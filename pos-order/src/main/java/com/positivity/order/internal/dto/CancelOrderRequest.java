package com.positivity.order.internal.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Data;

@Data
public class CancelOrderRequest {

    @NotBlank(message = "Cancellation reason must not be blank")
    private String cancellationReason;

    private UUID workOrderId;
    private UUID paymentId;
    private String idempotencyKey;
}