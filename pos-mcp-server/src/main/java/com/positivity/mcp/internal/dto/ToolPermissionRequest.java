package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.NonNull;

@Schema(description = "Request to grant a permission code to a discovered OpenAPI tool")
public record ToolPermissionRequest(
        @Schema(
                description = "Permission code that must be held for the tool to be selectable. Either the "
                        + "literal AUTHENTICATED or a domain:resource:action code.",
                example = "event-receiver:event_type:view",
                requiredMode = REQUIRED)
        @NonNull
        @NotBlank
        @Pattern(
                regexp = "AUTHENTICATED|[a-z0-9-]+:[a-z0-9_-]+:[a-z0-9_-]+",
                message = "must be AUTHENTICATED or a domain:resource:action code")
        String permissionCode) {}
