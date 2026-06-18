package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "Person details resolved from the people system")
public class PersonDTO {

    @Schema(description = "Internal person identifier", example = "1001", requiredMode = REQUIRED)
    private Long id;

    @Schema(description = "Person first name", example = "Jane", requiredMode = NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Person last name", example = "Doe", requiredMode = NOT_REQUIRED)
    private String lastName;

    @Schema(description = "Primary email address", example = "jane.doe@example.com", requiredMode = NOT_REQUIRED)
    private String primaryEmail;

    @Schema(description = "Secondary email address", example = "jane.alt@example.com", requiredMode = NOT_REQUIRED)
    private String secondaryEmail;

    @Schema(description = "Associated phone numbers", example = "[\"+1-555-0100\"]", requiredMode = NOT_REQUIRED)
    private List<String> phoneNumbers;

    @Schema(description = "Login username", example = "jdoe", requiredMode = NOT_REQUIRED)
    private String username;
}
