package com.positivity.inventory.internal.dto.cyclecount;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to submit a count for a cycle count task.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitCountRequest {

    @NotNull(message = "Task ID is required")
    private UUID taskId;

    @NotNull(message = "Auditor ID is required")
    private String auditorId;

    @NotNull(message = "Actual quantity is required")
    @Min(value = 0, message = "Quantity must be zero or positive")
    private Integer actualQuantity;
}
