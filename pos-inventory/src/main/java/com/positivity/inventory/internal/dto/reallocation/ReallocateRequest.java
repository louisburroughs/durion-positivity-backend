package com.positivity.inventory.internal.dto.reallocation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to reallocate available-to-promise stock for a stock item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReallocateRequest {

    @Schema(
            description = "Identifier of the stock item to reallocate",
            example = "01960003-0000-7000-8000-000000000100",
            requiredMode = REQUIRED)
    @NotNull
    private UUID stockItemId;

    @Schema(
            description = "Type of event that triggered the reallocation",
            example = "STOCK_RECEIPT",
            requiredMode = NOT_REQUIRED)
    private String triggerType;

    @Schema(
            description = "Identifier of the source record that triggered the reallocation",
            example = "01960003-0000-7000-8000-000000000101",
            requiredMode = NOT_REQUIRED)
    private String triggerReferenceId;
}
