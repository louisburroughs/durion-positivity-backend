package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.tenant.internal.enums.ContactRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Add or replace a contact on an account. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for an account contact")
public class AccountContactRequest {

    @Schema(description = "Contact's name", example = "Jordan Lee", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String name;

    @Schema(description = "Role on the account", requiredMode = REQUIRED)
    @NotNull
    private ContactRole role;

    @Schema(description = "Email address", example = "jordan@acme.example", requiredMode = REQUIRED)
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    @Schema(description = "Phone number", example = "+1 555 0100", requiredMode = NOT_REQUIRED)
    @Size(max = 32)
    private String phone;
}
