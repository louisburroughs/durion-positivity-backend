package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for creating a change request")
public class CreateChangeRequestDTO {
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @NotBlank(message = "description is required")
    @Schema(description = "Description of additional requested work", example = "Customer requested additional diagnostics")
    private String description;

    @Schema(description = "Whether this request contains emergency/safety exception items", example = "false")
    private Boolean isEmergencyException;

    @Schema(description = "Exception reason when emergency override context applies", example = "Safety-critical repair")
    private String exceptionReason;

    @Valid
    @Schema(description = "Service items included in this change request")
    private List<WorkorderItemDTO> services;

    @Valid
    @Schema(description = "Part items included in this change request")
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
        @Schema(description = "Service catalog entity identifier for service items", example = "550e8400-e29b-41d4-a716-446655440010")
        private UUID serviceEntityId;

        @Schema(description = "Product entity identifier for inventory part items", example = "550e8400-e29b-41d4-a716-446655440011")
        private UUID productEntityId;

        @Schema(description = "Product entity identifier for non-inventory part items", example = "550e8400-e29b-41d4-a716-446655440012")
        private UUID nonInventoryProductEntityId;

        @Schema(description = "Requested quantity for part line items", example = "1")
        private Integer quantity;

        @Schema(description = "Whether this item is emergency/safety related", example = "false")
        private Boolean isEmergencySafety;

        @Schema(description = "Photo evidence URL for emergency/safety documentation", example = "https://cdn.example.com/evidence/photo-123.jpg")
        private String photoEvidenceUrl;

        @Schema(description = "Emergency notes used when photo evidence is unavailable", example = "Brake line rupture visible after wheel removal")
        private String emergencyNotes;

        @Schema(description = "Flag indicating photo capture was not possible", example = "false")
        private Boolean photoNotPossible;
    }
}
