package com.positivity.inventory.internal.dto.shortage;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Resolution options computed for an inventory shortage on an allocation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortageResolutionResponse {
    @Schema(
            description = "Identifier of the allocation the resolution applies to",
            example = "01960003-0000-7000-8000-000000000015",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(description = "Stock keeping unit that was short", example = "SKU-10042", requiredMode = REQUIRED)
    @NotNull
    private String sku;

    @Schema(description = "Proposed options for resolving the shortage", requiredMode = REQUIRED)
    @NotNull
    private List<ResolutionOption> options;

    @Schema(
            description = "Whether results are partial and a banner should be shown to the user",
            example = "false",
            requiredMode = REQUIRED)
    private boolean partialResultsBanner;
}
