package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentStatus;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PeopleAvailabilityResponse {

    private UUID personId;

    private String firstName;

    private String lastName;

    private UUID locationId;

    private String role;

    private boolean primary;

    private AssignmentStatus assignmentStatus;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private LocalDate availableOn;
}
