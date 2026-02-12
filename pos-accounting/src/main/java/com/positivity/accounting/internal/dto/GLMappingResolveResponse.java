package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

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
