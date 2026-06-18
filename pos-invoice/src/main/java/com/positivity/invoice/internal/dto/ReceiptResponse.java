package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.ReceiptStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Result of generating a payment receipt")
public class ReceiptResponse {

    @NotNull
    @Schema(
            description = "Unique identifier of the generated receipt",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    private UUID receiptId;

    @Schema(description = "Human-readable receipt reference", example = "RCPT-2026-000789", requiredMode = NOT_REQUIRED)
    private String reference;

    @NotNull
    @Schema(description = "Current status of the receipt", example = "GENERATED", requiredMode = REQUIRED)
    private ReceiptStatus status;
}
