package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of resolving a scanned SKU and location against a pick task")
public class ResolveScanResponse {
    @NotNull
    @Schema(
            description = "Identifier of the pick task the scan resolved against",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID pickTaskId;

    @NotNull
    @Schema(
            description = "Identifier of the pick list containing the resolved task",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID pickListId;

    @Schema(
            description =
                    "Identifier of the SKU resolved for the scan (may differ from scanned SKU after substitution)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID resolvedSkuId;

    @Schema(
            description = "Identifier of the location resolved for the scan",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID resolvedLocationId;

    @Schema(
            description = "Whether the scan matched the expected SKU and location for the pick task",
            example = "true",
            requiredMode = REQUIRED)
    private boolean matched;

    @Schema(
            description = "Human-readable status describing the match outcome",
            example = "MATCHED",
            requiredMode = NOT_REQUIRED)
    private String matchStatus;
}
