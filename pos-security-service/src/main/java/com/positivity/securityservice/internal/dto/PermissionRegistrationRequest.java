package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for registering permissions from a service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to register the permission catalog published by a domain service")
public class PermissionRegistrationRequest {
    /**
     * The domain/service name registering these permissions
     */
    @Schema(
            description = "Domain that owns the permissions being registered",
            example = "people",
            requiredMode = REQUIRED)
    private String domain;

    /**
     * The service identifier
     */
    @Schema(
            description = "Service identifier registering the permissions",
            example = "pos-people-service",
            requiredMode = REQUIRED)
    private String serviceName;

    /**
     * List of permissions to register
     */
    @Schema(description = "Permission definitions to register or update", requiredMode = REQUIRED)
    private List<PermissionDefinition> permissions;

    /**
     * Version of the manifest schema
     */
    @Schema(description = "Manifest schema version", example = "1.0", requiredMode = NOT_REQUIRED)
    private String version = "1.0";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "A single permission definition within a registration manifest")
    public static class PermissionDefinition {
        /**
         * Permission name in format domain:resource:action
         */
        @JsonAlias({"id"})
        @Schema(
                description = "Permission name in format domain:resource:action",
                example = "people:timekeeping:view",
                requiredMode = REQUIRED)
        private String name;

        /**
         * Human-readable description
         */
        @Schema(
                description = "Human-readable description of the permission",
                example = "View timekeeping records",
                requiredMode = NOT_REQUIRED)
        private String description;
    }
}
