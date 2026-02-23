package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating/updating a mobile unit.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MobileUnitRequest {

    private String name;
    private UUID baseLocationId;
    private String status;
    private UUID travelBufferPolicyId;
    private String notes;
    private List<String> capabilityIds;
    private List<CoverageRuleRequest> coverageRules;
}
