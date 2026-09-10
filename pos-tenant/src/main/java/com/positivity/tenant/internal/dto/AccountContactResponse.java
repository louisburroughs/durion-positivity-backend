package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.tenant.internal.enums.ContactRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A contact on an account. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A contact on an account")
public class AccountContactResponse {

    @Schema(description = "Contact id", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Owning account id", requiredMode = REQUIRED)
    private UUID accountId;

    @Schema(description = "Contact's name", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "Role on the account", requiredMode = REQUIRED)
    private ContactRole role;

    @Schema(description = "Email address", requiredMode = REQUIRED)
    private String email;

    @Schema(description = "Phone number", requiredMode = NOT_REQUIRED)
    private String phone;

    @Schema(description = "Created at (ISO 8601)", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last changed at (ISO 8601)", requiredMode = REQUIRED)
    private Instant updatedAt;
}
