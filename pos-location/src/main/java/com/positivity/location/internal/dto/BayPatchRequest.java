package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Patch payload for bays.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class BayPatchRequest {

    private String name;
    private String bayType;
    private String status;
    private Integer maxConcurrentVehicles;
    private BayCapacityRequest capacity;
    private List<String> serviceCapabilityIds;
    private List<String> skillRequirementIds;
}
