package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for site default storage location configuration.
 *
 * Issue: CAP-214 #38
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload describing default storage location configuration for a site")
public class SiteDefaultsResponse {

    @Schema(
            description = "Identifier of the site",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID siteId;

    @Schema(
            description = "Identifier of the default staging storage location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID defaultStagingLocationId;

    @Schema(
            description = "Identifier of the default quarantine storage location",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID defaultQuarantineLocationId;
}
