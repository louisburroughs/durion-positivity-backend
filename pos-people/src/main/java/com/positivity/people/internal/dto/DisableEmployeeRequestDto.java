package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import lombok.Data;

import java.time.LocalDate;

@Data
public class DisableEmployeeRequestDto {

    private String disableReason;

    private AssignmentTerminationPolicy assignmentPolicy = AssignmentTerminationPolicy.IMMEDIATE;

    private LocalDate assignmentEndDate;

}
