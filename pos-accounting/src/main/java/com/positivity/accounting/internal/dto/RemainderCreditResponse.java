package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The credit issued for a payment's remainder (CAP:550 S35, #2524). A replay returns the credit the
 * first request issued.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The customer credit issued for a payment's unapplied remainder")
public class RemainderCreditResponse {

    @Schema(
            description = "The payment whose remainder was credited",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID paymentId;

    @Schema(
            description = "The request's idempotency key, as sent",
            example = "remainder-2026-10-06-017",
            requiredMode = REQUIRED)
    private String requestId;

    @Schema(
            description = "Identifier of the customer credit issued",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID creditId;

    @Schema(description = "Amount of the credit (the whole remainder)", example = "12.50", requiredMode = REQUIRED)
    private BigDecimal amount;

    @Schema(description = "Currency of the credit (ISO 4217)", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(
            description = "The payment's unapplied amount after the credit; always 0",
            example = "0.00",
            requiredMode = REQUIRED)
    private BigDecimal remainingAmount;

    @Schema(
            description = "When the credit was issued (ISO 8601)",
            example = "2026-10-06T08:00:00Z",
            requiredMode = REQUIRED)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant createdAt;
}
