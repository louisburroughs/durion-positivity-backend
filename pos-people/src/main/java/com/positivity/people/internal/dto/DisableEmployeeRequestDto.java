package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import java.time.LocalDate;
import lombok.Data;

@Data
public class DisableEmployeeRequestDto {

    private String disableReason;

    private AssignmentTerminationPolicy assignmentPolicy = AssignmentTerminationPolicy.IMMEDIATE;

    private LocalDate assignmentEndDate;
}
