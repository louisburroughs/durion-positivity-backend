package com.positivity.vehiclefitment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for creating a new vehicle applicability hint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateHintRequest {
    
    @NotNull(message = "Product ID is required")
    private Long productId;
    
    @NotEmpty(message = "At least one fitment tag is required")
    @Valid
    private List<FitmentTagDto> fitmentTags;
    
    private String createdBy;
}
