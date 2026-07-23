package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for registering a deposit / down-payment credit")
public class CreateDepositRequest {

    @Schema(description = "Sales order that took the deposit (idempotency anchor)", requiredMode = REQUIRED)
    @NotNull
    private UUID orderId;

    @Schema(
            description = "Source document type the deposit is held against",
            allowableValues = {"ESTIMATE", "WORKORDER", "ORDER"},
            requiredMode = REQUIRED)
    @NotBlank
    private String sourceType;

    @Schema(description = "Source document id", requiredMode = REQUIRED)
    @NotNull
    private UUID sourceId;

    @Schema(description = "Bill-to party, when known", requiredMode = NOT_REQUIRED)
    private String partyId;

    @Schema(description = "Deposit amount", example = "200.00", requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal amount;

    @Schema(description = "ISO-4217 currency; defaults to USD", example = "USD", requiredMode = NOT_REQUIRED)
    private String currencyCode;
}
