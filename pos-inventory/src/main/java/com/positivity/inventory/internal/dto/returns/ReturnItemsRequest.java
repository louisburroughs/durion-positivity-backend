package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to return one or more items issued against a workorder")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnItemsRequest {

    @Schema(
            description = "Identifier of the workorder the items are being returned against",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Free-text reason explaining why the items are being returned",
            example = "Customer cancelled remaining repair scope",
            requiredMode = REQUIRED)
    @NotBlank
    private String returnReason;

    @Schema(description = "Lines describing each SKU and quantity being returned", requiredMode = REQUIRED)
    @Valid
    @NotEmpty
    private List<ReturnItemLine> items;
}
