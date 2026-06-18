package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import lombok.Data;

@Data
@Schema(description = "Partial update of a user account; only supplied fields are applied")
public class UserUpdateRequest {
    @Schema(description = "New login username", example = "jane.doe", requiredMode = NOT_REQUIRED)
    private String username;

    @Schema(description = "New password for the account", requiredMode = NOT_REQUIRED)
    private String password;

    @Schema(description = "Replacement set of role names", example = "[\"SHOP_MGR\"]", requiredMode = NOT_REQUIRED)
    private Set<String> roles;
}
