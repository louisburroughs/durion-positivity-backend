package com.positivity.vehiclefitment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for filter products response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilterProductsResponse {
    private List<Long> productIds;
    private int count;
}
