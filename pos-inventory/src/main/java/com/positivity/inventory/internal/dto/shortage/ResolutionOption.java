package com.positivity.inventory.internal.dto.shortage;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A single proposed option for resolving an inventory shortage")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolutionOption {
    @Schema(description = "Kind of resolution being proposed", example = "SUBSTITUTE", requiredMode = REQUIRED)
    private ResolutionOptionType type;

    @Schema(
            description = "Part number of the suggested substitute, when applicable",
            example = "SKU-10042",
            requiredMode = NOT_REQUIRED)
    private String substitutePartNumber;

    @Schema(
            description = "Estimated unit cost of the resolution option",
            example = "42.50",
            requiredMode = NOT_REQUIRED)
    private BigDecimal unitCost;

    @Schema(
            description = "Estimated lead time in days to obtain the part",
            example = "5",
            requiredMode = NOT_REQUIRED)
    private Integer estimatedLeadTimeDays;

    @Schema(
            description = "Source that produced this resolution option",
            example = "CATALOG",
            requiredMode = NOT_REQUIRED)
    private String source;

    @Schema(
            description = "Confidence level in the resolution option",
            example = "HIGH",
            requiredMode = NOT_REQUIRED)
    private String confidence;

    @Schema(
            description = "Quality tier of the suggested substitute",
            example = "OEM",
            requiredMode = NOT_REQUIRED)
    private String qualityTier;
}
