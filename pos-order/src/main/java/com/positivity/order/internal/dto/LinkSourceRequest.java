package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request payload for linking an order to an originating source")
public class LinkSourceRequest {

    @Schema(
            description = "Type classification of the originating source",
            example = "WORKORDER",
            requiredMode = REQUIRED)
    @NotBlank
    private String sourceType;

    @Schema(
            description = "Identifier of the originating source record",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @NotBlank
    private String sourceId;
}
