package com.positivity.location.internal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for mobile unit endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MobileUnitResponse {

    private UUID id;
    private String name;
    private UUID baseLocationId;
    private String status;
    private UUID travelBufferPolicyId;
    private String notes;
    private List<String> capabilityIds;
    private Instant createdAt;
    private Instant updatedAt;
}
