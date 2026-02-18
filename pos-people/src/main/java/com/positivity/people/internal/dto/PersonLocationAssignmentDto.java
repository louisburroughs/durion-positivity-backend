package com.positivity.people.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Value
@Builder
public class PersonLocationAssignmentDto {
    UUID assignmentId;
    UUID locationId;
    UUID personId;
    String role;
    @JsonProperty("isPrimary")
    boolean isPrimary;
    LocalDate effectiveFrom;
    LocalDate effectiveTo;
    Instant createdAt;
    Instant updatedAt;
}
