package com.positivity.inventory.internal.dto.putaway;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to generate putaway tasks for goods received against a source receipt")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePutawayTasksRequest {

    @Schema(
            description = "Identifier of the receipt the received goods belong to",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    @NotBlank
    private String sourceReceiptId;

    /**
     * Legacy single-line request field.
     * Prefer using lineItems for new integrations.
     */
    @Schema(
            description = "Legacy single-line product identifier; prefer lineItems for new integrations",
            example = "01960003-0000-7000-8000-000000000061",
            requiredMode = NOT_REQUIRED)
    private String productId;

    /**
     * Legacy single-line request field.
     * Prefer using lineItems for new integrations.
     */
    @Schema(
            description = "Legacy single-line quantity received; prefer lineItems for new integrations",
            example = "12",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer quantity;

    @Schema(
            description = "Line items describing the products and quantities to be put away",
            requiredMode = NOT_REQUIRED)
    @Valid
    private List<PutawayLineItemRequest> lineItems;
}
