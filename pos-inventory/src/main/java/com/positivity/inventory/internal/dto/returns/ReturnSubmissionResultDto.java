package com.positivity.inventory.internal.dto.returns;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnSubmissionResultDto {
    private UUID returnId;
    private UUID workorderId;
    private int processedLines;
    private String status;
    private Instant processedAt;
}
