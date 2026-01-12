package com.positivity.vehiclefitment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for vehicle applicability hint response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HintResponse {
    private Long hintId;
    private Long productId;
    private List<FitmentTagDto> fitmentTags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
}
