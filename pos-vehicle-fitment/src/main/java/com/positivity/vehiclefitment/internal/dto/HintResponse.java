package com.positivity.vehiclefitment.internal.dto;

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
    private String hintId;
    private String productId;
    private List<FitmentTagDto> fitmentTags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
}
