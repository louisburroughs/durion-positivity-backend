package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.ScopeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Request DTO for creating a role assignment
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignmentRequest {
    private UUID userId;
    private UUID roleId;
    private ScopeType scopeType = ScopeType.GLOBAL;
    private Set<String> scopeLocationIds;
    private LocalDateTime effectiveStartDate;
    private LocalDateTime effectiveEndDate;
}
