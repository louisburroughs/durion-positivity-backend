package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

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
            description = "Clerk opening the session",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = REQUIRED)
    @NotBlank
    private String openedByClerkId;

    @Schema(
            description = "Shop location; defaults from the terminal's previous session when omitted",
            example = "01960003-0000-7000-8000-000000000090",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Starting drawer cash; defaults to the terminal's previous counted close when omitted",
            example = "150.00",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private BigDecimal openingFloat;
}
