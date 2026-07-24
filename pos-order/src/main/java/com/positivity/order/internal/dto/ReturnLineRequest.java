package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "One requested return line")
public class ReturnLineRequest {

    @Schema(description = "The sold line (order line id) being returned against", requiredMode = REQUIRED)
    @NotNull
    private UUID originalOrderLineId;

    @Schema(
            description = "Units to return (capped at the un-refunded remainder)",
            example = "1",
            requiredMode = REQUIRED)
    @Positive
    private int returnQty;

    @Schema(
            description = "Disposition of the returned item",
            allowableValues = {"RESTOCK", "SCRAP", "WARRANTY"},
            requiredMode = REQUIRED)
    @NotBlank
    private String condition;

    @Schema(description = "Returned serials, when the sale captured them", requiredMode = NOT_REQUIRED)
    private List<String> serialNumbers;
}
