package com.positivity.people.internal.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoleAssignment {

    private UUID id;

    private User user;

    private Role role;

    private ScopeType scopeType;

    private Set<UUID> scopeLocationIds;

    private LocalDate effectiveStartDate;

    private LocalDate effectiveEndDate;

    private Instant createdAt;

    private String createdBy;
}
