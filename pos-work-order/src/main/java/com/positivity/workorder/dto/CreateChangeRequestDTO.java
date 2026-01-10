package com.positivity.workorder.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateChangeRequestDTO {
    private Long workOrderId;
    private Long requestedByUserId;
    private String description;
    private Boolean isEmergencyException;
    private String exceptionReason;
    private List<WorkOrderItemDTO> services;
    private List<WorkOrderItemDTO> parts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkOrderItemDTO {
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
