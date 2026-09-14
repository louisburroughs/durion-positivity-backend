package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.ServicePositionAssignment;
import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * One entry of a workorder's service-position history (#1983).
 *
 * <p>A record rather than a Lombok bean, and mapped out of the entity at the service boundary, so
 * the lazily-loaded {@code workorder} association never escapes into serialization.
 */
@Schema(description = "One placement of a workorder on a service position, open or already released")
public record ServicePositionAssignmentRecord(
        @Schema(description = "History row id", example = "42")
        Long id,

        @Schema(description = "Kind of position", example = "BAY")
        ResourceType resourceType,

        @Schema(
                description = "Bay id, mobile-unit id, or — for HOLD — the site parked at",
                example = "550e8400-e29b-41d4-a716-446655440301")
        UUID resourceId,

        @Schema(description = "Site the placement happened at", example = "550e8400-e29b-41d4-a716-446655440300")
        UUID locationId,

        @Schema(description = "When the workorder was placed here", example = "2026-03-10T09:30:00")
        LocalDateTime assignedAt,

        @Schema(description = "Who placed it", example = "advisor@shop.example")
        String assignedBy,

        @Schema(description = "When it left, or null while it is still here", example = "2026-03-10T14:05:00")
        LocalDateTime releasedAt,

        @Schema(description = "Who released it, or null while it is still here", example = "advisor@shop.example")
        String releasedBy,

        @Schema(description = "Why it was placed here or given up", example = "Moved to parking")
        String reason,

        @Schema(description = "True for the placement in force now", example = "true")
        boolean current) {

    @NonNull
    public static ServicePositionAssignmentRecord fromEntity(@NonNull ServicePositionAssignment entity) {
        return new ServicePositionAssignmentRecord(
                entity.getId(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getLocationId(),
                entity.getAssignedAt(),
                entity.getAssignedBy(),
                entity.getReleasedAt(),
                entity.getReleasedBy(),
                entity.getReason(),
                Boolean.TRUE.equals(entity.getCurrent()));
    }
}
