package com.positivity.inventory.internal.dto.cyclecount;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for cycle count history entries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountEntryResponse {

    private UUID countEntryId;
    private UUID cycleCountTaskId;
    private String auditorId;
    private Integer actualQuantity;
    private Integer expectedQuantity;
    private Integer variance;
    private Integer recountSequenceNumber;
    private UUID recountOfCountEntryId;
    private Instant countedAt;
    private boolean recount;
}
