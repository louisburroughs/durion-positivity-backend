package com.positivity.vehiclefitment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO for filtering products by vehicle attributes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilterProductsRequest {
    private Map<String, String> vehicleAttributes;
}
