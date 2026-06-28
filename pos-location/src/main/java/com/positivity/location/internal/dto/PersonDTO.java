package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "Person reference associated with a location")
public class PersonDTO {

    @Schema(description = "Unique identifier of the person", example = "1001", requiredMode = NOT_REQUIRED)
    private Long id;

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

    @Schema(description = "Login username of the person", example = "jdoe", requiredMode = NOT_REQUIRED)
    private String username;
}
