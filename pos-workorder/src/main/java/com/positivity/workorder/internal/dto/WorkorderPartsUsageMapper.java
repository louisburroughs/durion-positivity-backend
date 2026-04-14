package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting WorkorderPartUsageEvent entities to DTOs.
 */
public final class WorkorderPartsUsageMapper {

    private WorkorderPartsUsageMapper() {
        // Utility class
    }

    /**
     * Convert entity to response DTO for write operations.
     */
    public static WorkorderPartUsageEventResponse toResponse(@NonNull WorkorderPartUsageEvent event) {
        return WorkorderPartUsageEventResponse.builder()
                .id(event.getId())
                .workorderPartId(event.getWorkorderPart().getId())
                .workorderId(event.getWorkorderId())
                .eventType(event.getEventType())
                .quantity(event.getQuantity())
                .performedBy(event.getPerformedBy())
                .performedAt(event.getPerformedAt())
                .notes(event.getNotes())
                .partDescription(event.getWorkorderPart().getDescription())
                .build();
    }
}
