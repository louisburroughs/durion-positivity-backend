package com.positivity.securityservice.internal.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RoleDto {
    UUID id;
    String name;
    String description;
    Set<PermissionDto> permissions;
    Instant createdAt;
    String createdBy;
    Instant lastModifiedAt;
    String lastModifiedBy;
}
