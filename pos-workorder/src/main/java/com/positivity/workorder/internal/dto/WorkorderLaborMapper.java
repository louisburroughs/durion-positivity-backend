package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting WorkorderLaborEntry entities to DTOs.
 */
public final class WorkorderLaborMapper {

    private WorkorderLaborMapper() {
        // Utility class
    }

    /**
     * Convert entity to response DTO.
     */
    public static WorkorderLaborEntryResponse toResponse(@NonNull WorkorderLaborEntry entry) {
        return WorkorderLaborEntryResponse.fromEntity(entry);
    }
}
