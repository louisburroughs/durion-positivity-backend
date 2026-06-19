package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response body for POST /v1/permissions/decode.
 *
 * @param permissions list of decoded permission code strings
 */
@Schema(description = "Decoded permission code strings")
public record PermissionDecodeResponse(
        @Schema(
                description = "List of decoded permission code strings",
                example = "[\"people:timekeeping:view\", \"people:timekeeping:edit\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> permissions) {}
