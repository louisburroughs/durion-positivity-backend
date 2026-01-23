package com.positivity.customer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for creating a vehicle record for a party.
 * Issue #169: Vehicle: Create Vehicle Record with VIN and Description
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateVehicleForPartyResponse {

    /**
     * Newly created vehicle ID
     */
    private String vehicleId;

    /**
     * Party ID (owner) of the vehicle
     */
    private String partyId;

    /**
     * Vehicle Identification Number
     */
    private String vinNumber;

    /**
     * Unit number or internal reference
     */
    private String unitNumber;

    /**
     * Vehicle description
     */
    private String description;

    /**
     * License plate
     */
    private String licensePlate;

    /**
     * Vehicle status (ACTIVE|INACTIVE)
     */
    private String status;

    /**
     * Timestamp of creation (ISO 8601)
     */
    private String createdAt;
}
