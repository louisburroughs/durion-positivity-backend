package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to link a user account to a person record")
public class LinkUserToPersonRequest {

    @NotNull(message = "userId is required")
    @Schema(description = "User account identifier", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID userId;

    @NotNull(message = "personId is required")
    @Schema(description = "Person identifier", example = "01960011-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(description = "Type of link between user and person", example = "PRIMARY", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String linkType = "PRIMARY";

    @Schema(description = "Optional notes describing the link", example = "Linked during onboarding", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String notes;

    public LinkUserToPersonRequest(UUID userId, UUID personId) {
        this.userId = userId;
        this.personId = personId;
    }
}
