package com.positivity.location.internal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for bay endpoints.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BayResponse {

    private UUID id;
    private UUID locationId;
    private String name;
    private String bayType;
    private String status;
    private Integer maxConcurrentVehicles;
    private List<String> serviceCapabilityIds;
    private List<String> skillRequirementIds;
    private Instant createdAt;
    private Instant lastModifiedAt;
}
