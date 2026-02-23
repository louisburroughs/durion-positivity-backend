package com.positivity.location.internal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for service area endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceAreaResponse {

    private UUID id;
    private String name;
    private String description;
    private Boolean active;
    private List<ServiceAreaRequest.PostalCodeEntry> postalCodes;
    private Instant createdAt;
    private Instant updatedAt;
}
