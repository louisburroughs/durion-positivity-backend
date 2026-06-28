package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Batch request resolving a set of workorder ids to their human workorder numbers.
 *
 * <p>Consumed server-side by sibling services (e.g. pos-invoice) that store only the
 * workorder id and need the human number to enrich finder/search rows.
 */
@Schema(description = "Batch request to resolve workorder ids to human workorder numbers")
public record WorkorderNumberResolveRequest(
        @Schema(description = "Workorder identifiers to resolve", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotEmpty
                @Size(max = 200)
                List<UUID> workorderIds) {

    public WorkorderNumberResolveRequest {
        workorderIds = workorderIds == null ? List.of() : List.copyOf(workorderIds);
    }
}
