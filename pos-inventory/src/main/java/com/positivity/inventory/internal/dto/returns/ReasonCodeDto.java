package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Reference entry describing a valid reason a returned item can be assigned")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasonCodeDto {
    @Schema(description = "Machine-readable reason code", example = "DAMAGED", requiredMode = REQUIRED)
    private String code;

    @Schema(
            description = "Human-readable explanation of the reason code",
            example = "Item arrived damaged or defective",
            requiredMode = REQUIRED)
    private String description;

    @Schema(
            description = "Grouping category the reason code belongs to",
            example = "QUALITY",
            requiredMode = NOT_REQUIRED)
    private String category;
}
