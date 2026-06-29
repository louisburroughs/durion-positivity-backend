package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
@Schema(description = "User account view returned by user-management endpoints")
public class UserDto {
    @Schema(description = "User identifier", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    UUID id;

    @Schema(description = "Login username", example = "jane.doe", requiredMode = REQUIRED)
    String username;

    @Schema(description = "Role names assigned to the user", example = "[\"SHOP_MGR\"]", requiredMode = REQUIRED)
    Set<String> roles;

    @Nullable
    @Schema(
            description = "Linked person identifier, when the account is linked to a person record",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    UUID personId;
}
