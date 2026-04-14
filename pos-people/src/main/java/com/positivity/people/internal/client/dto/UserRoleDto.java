package com.positivity.people.internal.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleDto {

    private String userId;

    private String roleCode;

    private UUID locationId;

    private LocalDate startDate;

    private LocalDate endDate;

    private Boolean active;

}