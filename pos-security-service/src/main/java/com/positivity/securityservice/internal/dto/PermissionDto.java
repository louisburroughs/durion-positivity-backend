package com.positivity.securityservice.internal.dto;

import java.util.UUID;
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
    UUID id;
    String name;
    String domain;
    String description;
    boolean deprecated;
}
