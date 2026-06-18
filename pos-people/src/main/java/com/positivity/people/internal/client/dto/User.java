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
@Schema(description = "User reference as returned by the security service")
public class User {

    @Schema(
            description = "User identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Username", example = "jane.smith", requiredMode = NOT_REQUIRED)
    private String username;
}
