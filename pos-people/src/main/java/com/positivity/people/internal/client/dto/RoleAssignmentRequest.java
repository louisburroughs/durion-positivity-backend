package com.positivity.people.internal.client.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignmentRequest {

	private UUID userId;

	@NotNull(message = "roleId is required")
	private UUID roleId;

	private ScopeType scopeType = ScopeType.GLOBAL;

	private Set<String> scopeLocationIds;

	private LocalDate effectiveStartDate;

	private LocalDate effectiveEndDate;

}