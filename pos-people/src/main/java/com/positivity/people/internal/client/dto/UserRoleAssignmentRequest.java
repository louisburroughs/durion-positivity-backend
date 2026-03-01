package com.positivity.people.internal.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleAssignmentRequest {
    private UUID userId;
    private String roleCode;
    private Set<UUID> locationIds;
    private UUID locationId;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
}
