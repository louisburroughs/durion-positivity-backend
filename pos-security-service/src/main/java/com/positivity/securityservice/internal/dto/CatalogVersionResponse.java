package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for GET /v1/permissions/catalog-version.
 *
 * @param version         current catalog version integer
 * @param permissionCount total number of permissions in the catalog
 */
@Schema(description = "Current permission catalog version and size")
public record CatalogVersionResponse(
        @Schema(
                description = "Current catalog version integer",
                example = "7",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int version,

        @Schema(
                description = "Total number of permissions in the catalog",
                example = "128",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int permissionCount) {}
