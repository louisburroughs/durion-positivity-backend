package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Person summary returned by person read operations")
public class PersonResponse {

    @Schema(description = "Person identifier", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;

    @Schema(description = "First name of the person", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Last name of the person", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String lastName;

    @Schema(description = "Primary email address", example = "jane.smith@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryEmail;

    @Schema(description = "Secondary email address", example = "jane.alt@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String secondaryEmail;

    @Schema(description = "Phone numbers on record", example = "[\"+1-555-123-4567\"]", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> phoneNumbers;

    @Schema(description = "Linked username, if any", example = "jane.smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String username;
}
