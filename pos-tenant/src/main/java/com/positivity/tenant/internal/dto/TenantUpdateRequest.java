package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Partial update of a tenant's mutable descriptors; slug and account are immutable. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for updating a tenant's display name or cell")
public class TenantUpdateRequest {

    @Schema(description = "New human-readable name; omitted or null leaves it unchanged", requiredMode = NOT_REQUIRED)
    @Size(min = 1, max = 200)
    private String displayName;

    @Schema(description = "New cell or region; omitted or null leaves it unchanged", requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String cell;
}
