package com.positivity.workorder.internal.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateChangeRequestDTO {
    private UUID workorderId;
    private String description;
    private Boolean isEmergencyException;
    private String exceptionReason;
    private List<WorkorderItemDTO> services;
    private List<WorkorderItemDTO> parts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkorderItemDTO {
        private UUID serviceEntityId;
        private UUID productEntityId;
        private UUID nonInventoryProductEntityId;
        private Integer quantity;
        private Boolean isEmergencySafety;
        private String photoEvidenceUrl;
        private String emergencyNotes;
        private Boolean photoNotPossible;
    }
}
