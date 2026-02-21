package com.positivity.securityservice.internal.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Permission DTO used by user-facing RBAC endpoints.
 *
 * Issue: #42
 */
@Value
@Builder
public class PermissionDto {
    String id;
    String domain;
    String description;
    boolean deprecated;
}
