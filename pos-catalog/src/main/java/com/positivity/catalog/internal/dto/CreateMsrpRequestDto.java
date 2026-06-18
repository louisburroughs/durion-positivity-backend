package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating a product MSRP")
public class CreateMsrpRequestDto {

    @NotNull
    @Schema(description = "MSRP amount", example = "99.99", requiredMode = REQUIRED)
    private BigDecimal amount;

    @NotNull
    @Schema(description = "ISO 4217 currency code", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @NotNull
    @Schema(description = "Date the MSRP becomes effective", example = "2026-01-15", requiredMode = REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(description = "Date the MSRP stops being effective", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveEndDate;

    @NotNull
    @Schema(
            description = "Identifier of the user creating the MSRP",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = REQUIRED)
    private UUID createdByUserId;
}
