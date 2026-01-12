package com.positivity.vehiclefitment.dto;

import com.positivity.vehiclefitment.entity.TagType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a fitment tag in API requests/responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FitmentTagDto {
    private TagType tagType;
    private String tagValue;
}
