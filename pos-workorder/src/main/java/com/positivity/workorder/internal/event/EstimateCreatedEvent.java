package com.positivity.workorder.internal.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a new Estimate is created.
 *
 * This event enables audit trails and downstream processing
 * (e.g., notifications, analytics) for estimate creation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EstimateCreatedEvent {

    /**
     * ID of the newly created Estimate
     */
    private UUID estimateId;

    /**
     * Generated estimate number (e.g., EST-2026-1000)
     */
    private String estimateNumber;

    /**
     * ID of the customer the estimate is for
     */
    private UUID customerId;

    /**
     * ID of the vehicle the estimate is for
     */
    private UUID vehicleId;

    /**
     * ID of the location where the estimate was created
     */
    private UUID locationId;

    /**
     * Username of the user who created the estimate
     */
    private String createdBy;

    /**
     * Timestamp when the estimate was created
     */
    private Instant timestamp;

    /**
     * Event type identifier for routing and logging
     */
    public String getEventType() {
        return "estimate.created.v1";
    }
}
