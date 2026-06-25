package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request to link a user account to a person record")
public class CreateUserLinkRequest {

    @NotNull(message = "username is required")
    @Schema(
            description = "Username of the security user account",
            example = "marcus.webb",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotNull(message = "personId is required")
    @Schema(
            description = "Person identifier",
            example = "01960011-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;
}
