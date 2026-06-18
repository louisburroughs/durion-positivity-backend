package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "Request payload for updating an existing cart item line")
public class UpdateItemRequest {

    @Schema(description = "Updated quantity for the item line", example = "3", requiredMode = REQUIRED)
    @Min(1)
    private int quantity;
}
