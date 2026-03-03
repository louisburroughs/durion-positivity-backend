package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Response payload for workorder part usage events")
public class WorkorderPartUsageEventResponse {

    @NonNull
    @Schema(description = "Usage event identifier", example = "550e8400-e29b-41d4-a716-446655440710")
    private UUID id;

    @NonNull
    @Schema(description = "Workorder part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID workorderPartId;

    @NonNull
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @NonNull
    @Schema(description = "Event type", example = "ISSUED")
    private String eventType;

    @NonNull
    @Schema(description = "Quantity associated with event", example = "1")
    private BigDecimal quantity;

    @NonNull
    @Schema(description = "Actor identifier who performed event", example = "tech@shop.local")
    private String performedBy;

    @NonNull
    @Schema(description = "Event timestamp")
    private Instant performedAt;

    @Nullable
    @Schema(description = "Optional event notes")
    private String notes;

    /**
     * Part description for display purposes
     */
    @Nullable
    @Schema(description = "Part description for display", example = "Brake Pad Set - Front")
    private String partDescription;
}
