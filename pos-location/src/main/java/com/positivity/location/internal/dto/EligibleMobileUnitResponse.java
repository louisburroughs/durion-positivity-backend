package com.positivity.location.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for eligible mobile unit search.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EligibleMobileUnitResponse {

    private UUID id;
    private String name;
    private UUID baseLocationId;
    private Integer priority;
}
