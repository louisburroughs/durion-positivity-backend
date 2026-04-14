package com.positivity.accounting.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from GL mapping resolution.
 * Returns the GL account ID that maps to the requested external code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLMappingResolveResponse {

    private UUID glAccountId;
}
