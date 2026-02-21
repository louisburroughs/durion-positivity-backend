package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for registering permissions from a service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionRegistrationRequest {
    /**
     * The domain/service name registering these permissions
     */
    private String domain;

    /**
     * The service identifier
     */
    private String serviceName;

    /**
     * List of permissions to register
     */
    private List<PermissionDefinition> permissions;

    /**
     * Version of the manifest schema
     */
    private String version = "1.0";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionDefinition {
        /**
         * Permission name in format domain:resource:action
         */
        @JsonAlias({ "id" })
        private String name;

        /**
         * Human-readable description
         */
        private String description;
    }
}
