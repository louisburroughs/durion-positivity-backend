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
@Schema(description = "Request to update the MSRP for a product")
public class UpdateMsrpRequestDto {

    @NotNull
    @Schema(description = "MSRP amount", example = "149.99", requiredMode = REQUIRED)
    private BigDecimal amount;

    @NotNull
    @Schema(description = "ISO currency code for the amount", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @NotNull
    @Schema(description = "Date the MSRP becomes effective", example = "2026-01-01", requiredMode = REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(
            description = "Date the MSRP stops being effective (null means open-ended)",
            example = "2026-12-31",
            requiredMode = NOT_REQUIRED)
    private LocalDate effectiveEndDate;

    @NotNull
    @Schema(
            description = "Identifier of the user creating this MSRP entry",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID createdByUserId;

    @Schema(description = "Version for optimistic locking", example = "1", requiredMode = NOT_REQUIRED)
    private Long version;
}
