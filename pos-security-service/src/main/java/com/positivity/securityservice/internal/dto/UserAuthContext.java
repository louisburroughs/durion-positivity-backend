package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Internal authentication context carrying credentials and roles for a user")
public class UserAuthContext {
    @Schema(
            description = "User identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID id;

    @Schema(description = "Login username", example = "jane.doe", requiredMode = REQUIRED)
    String username;

    @Schema(description = "Hashed password used for credential verification", requiredMode = REQUIRED)
    String passwordHash;

    @Schema(description = "Role names assigned to the user", example = "[\"SHOP_MGR\"]", requiredMode = REQUIRED)
    Set<String> roles;
}
