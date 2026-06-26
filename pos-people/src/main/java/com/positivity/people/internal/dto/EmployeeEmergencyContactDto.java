package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Emergency contact details for an employee")
public class EmployeeEmergencyContactDto {

    @Schema(
            description = "Name of the emergency contact",
            example = "John Smith",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Relationship to the employee",
            example = "Spouse",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String relationship;

    @Schema(
            description = "Phone number of the emergency contact",
            example = "+1-555-222-3333",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String phone;

    @Schema(
            description = "Email address of the emergency contact",
            example = "john.smith@example.com",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String email;
}
