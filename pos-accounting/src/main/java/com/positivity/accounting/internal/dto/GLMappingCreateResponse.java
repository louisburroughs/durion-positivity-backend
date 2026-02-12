package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wrapper response for GL mapping creation.
 * Follows pattern expected by integration tests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLMappingCreateResponse {

    private GLMappingResponse mapping;
}
