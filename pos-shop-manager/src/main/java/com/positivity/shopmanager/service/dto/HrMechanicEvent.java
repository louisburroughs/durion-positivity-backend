package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.service.enums.HrEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Inbound HR integration event carrying mechanic data from the HR system.
 * This is a public contract type on the service boundary (ADR-0026).
 */
@Data
@Builder
@Schema(description = "Inbound HR integration event carrying mechanic data from the HR system")
public class HrMechanicEvent {

    @Schema(
            description = "Unique event identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID eventId;

    @Schema(description = "Type of HR mechanic event", example = "MECHANIC_UPSERTED", requiredMode = REQUIRED)
    private HrEventType eventType;

    @Schema(
            description = "Person identifier of the mechanic the event concerns",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private String personId;

    @Schema(description = "Monotonic version of the mechanic record", example = "3", requiredMode = NOT_REQUIRED)
    private Integer version;

    @Schema(
            description = "Instant the event occurred in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant occurredAt;

    @Schema(description = "Event payload carrying mechanic attributes", requiredMode = NOT_REQUIRED)
    private Payload payload;

    @Data
    @Builder
    @Schema(description = "Payload of an HR mechanic event")
    public static class Payload {

        @Schema(description = "Mechanic first name", example = "Jane", requiredMode = NOT_REQUIRED)
        private String firstName;

        @Schema(description = "Mechanic last name", example = "Doe", requiredMode = NOT_REQUIRED)
        private String lastName;

        @Schema(description = "Mechanic hire date (ISO-8601)", example = "2024-03-15", requiredMode = NOT_REQUIRED)
        private LocalDate hireDate;

        @Schema(description = "Mechanic skills with proficiency levels", requiredMode = NOT_REQUIRED)
        private List<Skill> skills;

        @Data
        @Builder
        @Schema(description = "A single mechanic skill and proficiency level")
        public static class Skill {

            @Schema(description = "Skill code", example = "BRAKES", requiredMode = REQUIRED)
            private String skillCode;

            @Schema(description = "Proficiency level for the skill", example = "4", requiredMode = REQUIRED)
            private int proficiencyLevel;
        }
    }
}
