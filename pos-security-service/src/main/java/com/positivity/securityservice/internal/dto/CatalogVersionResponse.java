package com.positivity.securityservice.internal.dto;

/**
 * Response for GET /v1/permissions/catalog-version.
 *
 * @param version         current catalog version integer
 * @param permissionCount total number of permissions in the catalog
 */
public record CatalogVersionResponse(int version, int permissionCount) {}
