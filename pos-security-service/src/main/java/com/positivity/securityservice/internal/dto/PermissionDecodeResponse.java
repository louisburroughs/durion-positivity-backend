package com.positivity.securityservice.internal.dto;

import java.util.List;

/**
 * Response body for POST /v1/permissions/decode.
 *
 * @param permissions list of decoded permission code strings
 */
public record PermissionDecodeResponse(List<String> permissions) {}
