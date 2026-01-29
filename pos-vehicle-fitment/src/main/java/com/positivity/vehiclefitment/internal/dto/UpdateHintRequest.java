package com.positivity.vehiclefitment.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for updating an existing vehicle applicability hint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateHintRequest {
    
    @NotEmpty(message = "At least one fitment tag is required")
    @Valid
    private List<FitmentTagDto> fitmentTags;
    
    private String updatedBy;
}
