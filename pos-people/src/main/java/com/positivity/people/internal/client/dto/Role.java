package com.positivity.people.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Role reference as returned by the security service")
public class Role {

    @Schema(
            description = "Role identifier",
            example = "01960011-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Human-readable role name", example = "Technician", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Description of the role", example = "Performs service work on vehicles", requiredMode = NOT_REQUIRED)
    private String description;
}
