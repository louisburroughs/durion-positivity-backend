package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonRoleAssignmentRequest {

    @NotBlank
    private String roleCode;

    private UUID locationId;

    private LocalDateTime startDate;

    private LocalDateTime endDate;
}
