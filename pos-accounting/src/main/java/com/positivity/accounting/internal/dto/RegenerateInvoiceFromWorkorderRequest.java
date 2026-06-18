package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request to regenerate an invoice from a completed workorder")
public class RegenerateInvoiceFromWorkorderRequest {

    @Schema(
            description = "Identifier of the workorder to regenerate the invoice from",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Optional idempotency key to safely retry regeneration",
            example = "01960003-0000-7000-8000-000000000099",
            requiredMode = NOT_REQUIRED)
    private String idempotencyKey;
}
