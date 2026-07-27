package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request payload for voiding an unsettled order")
public class VoidOrderRequest {

    @Schema(
            description = "Reason the order is being voided",
            example = "Customer walked away before payment",
            requiredMode = NOT_REQUIRED)
    private String reason;
}
