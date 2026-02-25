package com.positivity.inventory.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

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
    private UUID locationId;
    private String locationName;
    private int onHandQuantity;
    private int availableToPromiseQuantity;
}
