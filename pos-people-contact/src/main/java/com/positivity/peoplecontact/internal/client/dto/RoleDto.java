package com.positivity.peoplecontact.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Role definition as exposed by the security service")
public class RoleDto {

    @Schema(description = "Stable role code", example = "TECHNICIAN", requiredMode = NOT_REQUIRED)
    private String code;

    @Schema(description = "Human-readable role name", example = "Technician", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Description of the role",
            example = "Performs service work on vehicles",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Scope at which the role applies", example = "LOCATION", requiredMode = NOT_REQUIRED)
    private String scopeType;

    @Schema(description = "Whether the role is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;
}
