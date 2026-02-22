package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.ScopeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RoleAssignmentDto {
    UUID id;
    UUID userId;
    UUID roleId;
    ScopeType scopeType;
    Set<String> scopeLocationIds;
    LocalDateTime effectiveStartDate;
    LocalDateTime effectiveEndDate;
    Instant revokedAt;
    Instant createdAt;
    String createdBy;
    Instant lastModifiedAt;
    String lastModifiedBy;
}

