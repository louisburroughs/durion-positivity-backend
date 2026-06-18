package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Schema(description = "Request to resolve an inventory shortage on an allocation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageResolveRequest {

    @Schema(
            description = "Identifier of the allocation to resolve",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(
            description = "Identifier of the specific allocation line to resolve, when line-scoped",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID allocationLineId;

    @Schema(
            description = "Resolution strategy to apply to the shortage",
            example = "SUBSTITUTE",
            requiredMode = REQUIRED)
    @NotBlank
    private String resolution;

    @Schema(
            description = "Substitute stock-keeping unit to use, when the resolution is a substitution",
            example = "SKU-10043",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String substituteSku;

    @Schema(
            description = "Optional free-text notes explaining the resolution",
            example = "Customer approved substitute item",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String notes;
}
