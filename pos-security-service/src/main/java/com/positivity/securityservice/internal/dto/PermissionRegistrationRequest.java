package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
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
         * Backward-compatible convenience constructor for the common non-deprecated case.
         *
         * @param name        Permission name in format domain:resource:action
         * @param description Human-readable description
         */
        public PermissionDefinition(String name, String description) {
            this(name, description, false, null);
        }

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

        /**
         * Whether this permission is retired and should no longer be granted
         */
        @Schema(
                description = "Whether this permission is retired and should no longer be granted",
                example = "false",
                requiredMode = NOT_REQUIRED)
        private boolean deprecated = false;

        /**
         * Name of the permission that replaces this one, if any
         */
        @Size(max = 255, message = "supersededBy must not exceed 255 characters")
        @Schema(
                description = "Permission name that replaces this one; absent when there is no successor",
                example = "accounting:ap:pay",
                requiredMode = NOT_REQUIRED)
        private String supersededBy;
    }
}
