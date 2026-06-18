package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for configuring default storage locations for a site.
 *
 * Issue: CAP-214 #38
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for configuring default storage locations for a site")
public class SiteDefaultsRequest {

    @Schema(
            description = "Identifier of the default staging storage location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID defaultStagingLocationId;

    @Schema(
            description = "Identifier of the default quarantine storage location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID defaultQuarantineLocationId;
}
