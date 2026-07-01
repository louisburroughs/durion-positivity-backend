package com.positivity.mcp.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "The permission codes currently granted to a discovered OpenAPI tool")
public record ToolPermissionsResponse(
        @Schema(description = "Unique tool name", example = "event-receiver_getactiveeventtypes") @NonNull
        String toolName,

        @Schema(description = "Granted permission codes (a caller holding any one may select the tool)") @NonNull
        List<String> permissionCodes) {}
