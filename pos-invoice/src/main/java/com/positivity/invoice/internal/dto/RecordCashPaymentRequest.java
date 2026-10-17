package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /v1/invoices/{invoiceId}/payments/cash} (ADR-0067 A8, #2662): the register's cash tender as pos-order
 * computed it. pos-invoice records it CAPTURED (no gateway), checks the rounding triple against {@code cashIncrement}
 * and publishes {@code payment.payment.settled} with the three additive fields.
 */
@Data
@Schema(
        description = "Record a cash tender taken at the register: the amount applied, the cash taken after rounding"
                + " and the signed rounding")
public class RecordCashPaymentRequest {

    @NotBlank
    @Size(max = 128)
    @Schema(
            description = "Client-provided idempotency key for safe retry (the register's tender key)",
            example = "cash-01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private String idempotencyKey;

    @NotNull
    @Positive
    @Digits(integer = 13, fraction = 2)
    @Schema(
            description = "The amount applied to the invoice: the due before rounding",
            example = "10.02",
            requiredMode = REQUIRED)
    private BigDecimal amount;

    @NotNull
    @PositiveOrZero
    @Digits(integer = 13, fraction = 2)
    @Schema(
            description = "The cash actually taken, after rounding to the currency's cash increment",
            example = "10.00",
            requiredMode = REQUIRED)
    private BigDecimal settledAmount;

    @NotNull
    @Digits(integer = 13, fraction = 2)
    @Schema(
            description = "Signed rounding, settledAmount minus amount (negative when rounded down)",
            example = "-0.02",
            requiredMode = REQUIRED)
    private BigDecimal cashRoundingAmount;

    @Nullable
    @Positive
    @Digits(integer = 13, fraction = 2)
    @Schema(
            description = "The cash increment applied (0.05 for CAD); omitted when the currency has none, then"
                    + " settledAmount equals amount and the rounding is 0.00",
            example = "0.05",
            requiredMode = NOT_REQUIRED)
    private BigDecimal cashIncrement;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$")
    @Schema(description = "ISO 4217 code; must be the invoice's currency", example = "CAD", requiredMode = REQUIRED)
    private String currencyCode;

    @Nullable
    @Schema(description = "The register session (drawer) that holds the cash", requiredMode = NOT_REQUIRED)
    private UUID sessionId;

    @Nullable
    @Size(max = 100)
    @Schema(description = "Terminal that took the tender", requiredMode = NOT_REQUIRED)
    private String terminalId;

    @Nullable
    @Size(max = 100)
    @Schema(description = "Cashier who took the tender", requiredMode = NOT_REQUIRED)
    private String cashierId;
}
