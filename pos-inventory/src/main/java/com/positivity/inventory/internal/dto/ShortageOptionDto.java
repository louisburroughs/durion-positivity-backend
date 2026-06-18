package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Schema(description = "A candidate option for resolving an inventory shortage on an allocation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageOptionDto {

    @Schema(
            description = "Identifier of the allocation the option applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(
            description = "Identifier of the specific allocation line, when the option is line-scoped",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID allocationLineId;

    @Schema(
            description = "Resolution strategy offered for the shortage",
            example = "SUBSTITUTE",
            requiredMode = REQUIRED)
    @NotNull
    private String resolution;

    @Schema(
            description = "Human-readable description of the resolution option",
            example = "Substitute with equivalent SKU available at main warehouse",
            requiredMode = REQUIRED)
    @NotNull
    private String description;

    @Schema(
            description = "Substitute stock-keeping unit offered, when the resolution is a substitution",
            example = "SKU-10043",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String substituteSku;

    @Schema(
            description = "Estimated number of days to fulfill using this option",
            example = "3",
            requiredMode = REQUIRED)
    private int estimatedDays;
}
