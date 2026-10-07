package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Data;

/**
 * Request payload for opening a register session. The opening float is the register's configured float
 * from accounting and the opener comes from the security context (CAP:550 S16, #2512; AW16, ADR-0018):
 * an {@code openingFloat} or {@code openedByClerkId} a client still sends is ignored.
 */
@Data
@Schema(description = "Request payload for opening a register session")
public class OpenSessionRequest {

    @Schema(
            description = "Terminal the drawer belongs to; one open session per terminal",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    @NotBlank
    private String terminalId;

    @Schema(
            description = "Shop location; defaults from the terminal's previous session when omitted",
            example = "01960003-0000-7000-8000-000000000090",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;
}
