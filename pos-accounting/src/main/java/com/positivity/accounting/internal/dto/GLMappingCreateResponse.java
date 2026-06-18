package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Wrapper response for GL mapping creation")
public class GLMappingCreateResponse {

    @Schema(description = "The created GL mapping", requiredMode = REQUIRED)
    @NotNull
    private GLMappingResponse mapping;
}
