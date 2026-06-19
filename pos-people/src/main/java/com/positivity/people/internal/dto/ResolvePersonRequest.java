package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload to resolve (match or create) a person record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Resolve person request with weighted matching inputs")
public class ResolvePersonRequest {

    @Schema(description = "Email used for matching", example = "jane.smith@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String email;

    @Schema(description = "Phone number used for matching", example = "+1-555-123-4567", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String phone;

    @Schema(description = "Last name used for matching", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String lastName;

    @Schema(description = "First name used for matching", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Optional score threshold override", example = "30", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer threshold;
}
