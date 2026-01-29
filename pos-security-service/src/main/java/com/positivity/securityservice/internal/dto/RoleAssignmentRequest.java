package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.model.ScopeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Set;

/**
 * Request DTO for creating a role assignment
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignmentRequest {
    private Long userId;
    private Long roleId;
    private ScopeType scopeType = ScopeType.GLOBAL;
    private Set<String> scopeLocationIds;
    private LocalDate effectiveStartDate;
    private LocalDate effectiveEndDate;
}
