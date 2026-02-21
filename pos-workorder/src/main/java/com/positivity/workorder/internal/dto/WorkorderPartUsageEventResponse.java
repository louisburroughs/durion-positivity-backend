package com.positivity.workorder.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a parts usage event.
 * 
 * CAP:005 Story #158 - Parts Usage Tracking
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkorderPartUsageEventResponse {

    @NonNull
    private UUID id;

    @NonNull
    private UUID workorderPartId;

    @NonNull
    private UUID workorderId;

    @NonNull
    private String eventType;

    @NonNull
    private BigDecimal quantity;

    @NonNull
    private String performedBy;

    @NonNull
    private Instant performedAt;

    @Nullable
    private String notes;

    /**
     * Part description for display purposes
     */
    @Nullable
    private String partDescription;
}
