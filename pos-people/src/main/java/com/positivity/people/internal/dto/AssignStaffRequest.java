package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class AssignStaffRequest {

    @NotNull(message = "personId is required")
    private UUID personId;

    private String role;

    private Boolean isPrimary = Boolean.FALSE;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;
}
