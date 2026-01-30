package com.positivity.workorder.internal.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateChangeRequestDTO {
    private Long workorderId;
    private Long requestedByUserId;
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
        private Long serviceEntityId;
        private Long productEntityId;
        private Long nonInventoryProductEntityId;
        private Integer quantity;
        private Boolean isEmergencySafety;
        private String photoEvidenceUrl;
        private String emergencyNotes;
        private Boolean photoNotPossible;
    }
}
