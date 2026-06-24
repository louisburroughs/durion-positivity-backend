package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.WorkorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight workorder search row enriched with the resolved customer display name.
 *
 * <p>Returned by {@code GET /v1/workorders/search?q=...} which matches the query against
 * the customer name or the workorder id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Lightweight workorder search result enriched with customer name")
public class WorkorderSearchResult {

    @Schema(
            description = "Workorder identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(description = "Current workorder status", example = "DRAFT", requiredMode = REQUIRED)
    private WorkorderStatus status;

    @Schema(description = "Resolved customer display name", requiredMode = NOT_REQUIRED)
    private String customerName;

    @Schema(description = "Creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;
}
