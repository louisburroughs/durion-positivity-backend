package com.positivity.inventory.internal.dto.cyclecount;

import com.positivity.inventory.internal.enums.TaskStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response after submitting a count or recount.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountResponse {

    private UUID countEntryId;
    private UUID taskId;
    private Integer actualQuantity;
    private Integer expectedQuantity;
    private Integer variance;
    private Integer recountSequenceNumber;
    private TaskStatus taskStatus;
    private Instant countedAt;
    private boolean limitExceeded;
    private String message;
}
