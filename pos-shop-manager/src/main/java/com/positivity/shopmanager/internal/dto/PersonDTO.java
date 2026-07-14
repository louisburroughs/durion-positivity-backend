package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Person details for a technician")
public class PersonDTO {

    @Schema(
            description = "People-contact person identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @Schema(description = "First name of the person", example = "Jane", requiredMode = NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Last name of the person", example = "Doe", requiredMode = NOT_REQUIRED)
    private String lastName;

    @Schema(
            description = "Primary email address of the person",
            example = "jane.doe@example.com",
            requiredMode = NOT_REQUIRED)
    private String primaryEmail;

    @Schema(
            description = "Secondary email address of the person",
            example = "jane.d@example.com",
            requiredMode = NOT_REQUIRED)
    private String secondaryEmail;

    @Schema(
            description = "Phone numbers associated with the person",
            example = "[\"+1-217-555-0100\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> phoneNumbers;
}
