package com.positivity.customer.internal.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a vehicle record for a party.
 * Issue #169: Vehicle: Create Vehicle Record with VIN and Description
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateVehicleForPartyRequest {

    /**
     * Vehicle Identification Number (required, uniqueness scope TBD: global or
     * per-party)
     */
    private String vinNumber;

    /**
     * Unit number or internal reference (optional)
     */
    private String unitNumber;

    /**
     * Vehicle description (optional)
     */
    private String description;

    /**
     * License plate (optional).
     * Format TBD: single string or plate + region (state/province/country).
     */
    private String licensePlate;

    /**
     * License plate region/state (optional, if separate field required)
     */
    private String licensePlateRegion;
}
