package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

@Data
@Schema(description = "Contact information for an employee")
public class EmployeeContactInfoDto {

    @Schema(description = "Primary email address", example = "jane.smith@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryEmail;

    @Schema(description = "Primary phone number", example = "+1-555-123-4567", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryPhone;

    @Schema(description = "Secondary email address", example = "jane.alt@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String secondaryEmail;

    @Schema(description = "Secondary phone number", example = "+1-555-987-6543", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String secondaryPhone;

    @Valid
    @Schema(description = "Postal address", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private EmployeeAddressDto address;

    @Valid
    @Schema(description = "Emergency contact details", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private EmployeeEmergencyContactDto emergencyContact;
}
