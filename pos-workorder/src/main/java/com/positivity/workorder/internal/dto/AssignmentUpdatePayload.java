package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Assignment context payload applied to a workorder")
public class AssignmentUpdatePayload {
    @NotNull(message = "locationId is required")
    @Schema(
            description = "Assigned location identifier",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = REQUIRED)
    private UUID locationId;

    @NotNull(message = "resourceId is required")
    @Schema(
            description = "Assigned primary resource identifier",
            example = "550e8400-e29b-41d4-a716-446655440301",
            requiredMode = REQUIRED)
    private UUID resourceId;

    /**
     * Which resource aggregate {@code resourceId} names (#1656).
     *
     * <p>Deliberately optional: the upstream pos-shop-manager assignment publisher does not emit
     * this field yet and cannot be changed from this module. An absent value resolves to
     * {@code BAY} through {@link ResourceType#orDefault(ResourceType)} — the pre-#1656 behaviour,
     * where every assignment was assumed to be a bay. Once shopmgmt publishes the field, that
     * fallback stops being reached; the contract follow-up is tracked with this story.
     */
    @Schema(
            description = "Kind of resource the assignment points at. Optional: an absent value is "
                    + "interpreted as BAY, which is what every assignment meant before mobile units "
                    + "were representable.",
            example = "MOBILE_UNIT",
            requiredMode = NOT_REQUIRED)
    private ResourceType resourceType;

    @Schema(
            description = "Assigned mechanic identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440120\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> mechanicIds;
}
