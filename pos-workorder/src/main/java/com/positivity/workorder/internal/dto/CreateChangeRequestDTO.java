package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for creating a change request")
public class CreateChangeRequestDTO {
    @Schema(
            description = "Workorder identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID workorderId;

    @NotBlank(message = "description is required")
    @Schema(
            description = "Description of additional requested work",
            example = "Customer requested additional diagnostics",
            requiredMode = REQUIRED)
    private String description;

    @Schema(
            description = "Whether this request contains emergency/safety exception items",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean isEmergencyException;

    @Schema(
            description = "Exception reason when emergency override context applies",
            example = "Safety-critical repair",
            requiredMode = NOT_REQUIRED)
    private String exceptionReason;

    @Valid
    @Schema(description = "Service items included in this change request", example = "[]", requiredMode = NOT_REQUIRED)
    private List<WorkorderItemDTO> services;

    @Valid
    @Schema(description = "Part items included in this change request", example = "[]", requiredMode = NOT_REQUIRED)
    private List<WorkorderItemDTO> parts;

    @AssertTrue(message = "At least one service or part item is required")
    @Schema(hidden = true)
    private boolean hasAtLeastOneItem() {
        return (services != null && !services.isEmpty()) || (parts != null && !parts.isEmpty());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Service or part line item included in a change request")
    public static class WorkorderItemDTO {
        @Schema(
                description = "Service catalog entity identifier for service items",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = NOT_REQUIRED)
        private UUID serviceEntityId;

        @Schema(
                description = "Product entity identifier for inventory part items",
                example = "01960003-0000-7000-8000-000000000011",
                requiredMode = NOT_REQUIRED)
        private UUID productEntityId;

        @Schema(
                description = "Product entity identifier for non-inventory part items",
                example = "01960003-0000-7000-8000-000000000012",
                requiredMode = NOT_REQUIRED)
        private UUID nonInventoryProductEntityId;

        @Schema(description = "Requested quantity for part line items", example = "2", requiredMode = NOT_REQUIRED)
        private Integer quantity;

        @Schema(
                description = "Whether this item is emergency/safety related",
                example = "false",
                requiredMode = NOT_REQUIRED)
        private Boolean isEmergencySafety;

        @Schema(
                description = "Photo evidence URL for emergency/safety documentation",
                example = "https://cdn.example.com/evidence/photo-123.jpg",
                requiredMode = NOT_REQUIRED)
        private String photoEvidenceUrl;

        @Schema(
                description = "Emergency notes used when photo evidence is unavailable",
                example = "Brake line rupture visible after wheel removal",
                requiredMode = NOT_REQUIRED)
        private String emergencyNotes;

        @Schema(
                description = "Flag indicating photo capture was not possible",
                example = "false",
                requiredMode = NOT_REQUIRED)
        private Boolean photoNotPossible;
    }
}
