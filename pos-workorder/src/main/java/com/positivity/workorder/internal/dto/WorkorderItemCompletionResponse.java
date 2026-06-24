package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of completing an individual workorder line item (service or part). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of completing a workorder line item")
public class WorkorderItemCompletionResponse {

    @Schema(description = "Workorder identifier", requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(description = "Line item identifier", requiredMode = REQUIRED)
    @NotNull
    private UUID itemId;

    @Schema(description = "Item kind", example = "SERVICE", requiredMode = REQUIRED)
    @NotNull
    private ItemType itemType;

    @Schema(description = "Resulting item status", example = "COMPLETED", requiredMode = REQUIRED)
    @NotNull
    private WorkorderItemStatus status;

    public enum ItemType {
        SERVICE,
        PART
    }
}
