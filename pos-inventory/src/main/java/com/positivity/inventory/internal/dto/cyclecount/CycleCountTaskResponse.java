package com.positivity.inventory.internal.dto.cyclecount;

import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.inventory.internal.entity.TaskStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for cycle count task query operations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountTaskResponse {

    private UUID taskId;
    private String binLocation;
    private String itemSku;
    private String itemDescription;
    private Integer expectedQuantity;
    private String auditorId;
    private TaskStatus status;
    private UUID latestCountEntryId;
    private Integer countEntriesCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
