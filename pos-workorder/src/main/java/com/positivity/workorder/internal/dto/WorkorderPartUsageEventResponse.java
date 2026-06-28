package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
    @NotNull
    @Schema(
            description = "Usage event identifier",
            example = "550e8400-e29b-41d4-a716-446655440710",
            requiredMode = REQUIRED)
    private UUID id;

    @NonNull
    @NotNull
    @Schema(
            description = "Workorder part identifier",
            example = "550e8400-e29b-41d4-a716-446655440500",
            requiredMode = REQUIRED)
    private UUID workorderPartId;

    @NonNull
    @NotNull
    @Schema(
            description = "Workorder identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @NonNull
    @NotNull
    @Schema(description = "Event type", example = "ISSUED", requiredMode = REQUIRED)
    private String eventType;

    @NonNull
    @NotNull
    @Schema(description = "Quantity associated with event", example = "1", requiredMode = REQUIRED)
    private BigDecimal quantity;

    @NonNull
    @NotNull
    @Schema(description = "Actor identifier who performed event", example = "tech@shop.local", requiredMode = REQUIRED)
    private String performedBy;

    @NonNull
    @NotNull
    @Schema(description = "Event timestamp", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant performedAt;

    @Nullable
    @Schema(description = "Optional event notes", example = "Issued from main bay", requiredMode = NOT_REQUIRED)
    private String notes;

    /**
     * Part description for display purposes
     */
    @Nullable
    @Schema(
            description = "Part description for display",
            example = "Brake Pad Set - Front",
            requiredMode = NOT_REQUIRED)
    private String partDescription;
}
