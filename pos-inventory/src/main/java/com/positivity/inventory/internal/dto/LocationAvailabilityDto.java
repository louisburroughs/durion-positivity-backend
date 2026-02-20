package com.positivity.inventory.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-location availability projection for inventory availability queries.
 *
 * Issue: CAP-170 (#48)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationAvailabilityDto {
    private String locationId;
    private String locationName;
    private int onHandQuantity;
    private int availableToPromiseQuantity;
}
