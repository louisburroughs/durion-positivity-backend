package com.positivity.workorder.internal.dto;

import org.jspecify.annotations.NonNull;

import com.positivity.workorder.internal.entity.WorkorderLaborEntry;

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
