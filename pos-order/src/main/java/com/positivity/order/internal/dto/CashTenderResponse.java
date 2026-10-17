package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What the register shows after a cash tender (ADR-0067 A8, #2662): the figures the drawer arithmetic produced and
 * the pos-invoice intent that recorded them. The order's own balance follows from the settled fact within seconds.
 */
@Schema(
        description =
                "Outcome of a register cash tender: the amount applied, the cash due after rounding to the"
                        + " currency's cash increment, the signed rounding, the change and the payment intent pos-invoice recorded.")
public record CashTenderResponse(
        @Schema(description = "Order the tender applies to") @NonNull
        UUID orderId,

        @Schema(description = "Fronting invoice the cash was applied to") @NonNull
        UUID invoiceId,

        @Schema(description = "Payment intent pos-invoice recorded (CASH, CAPTURED)") @NonNull
        UUID paymentIntentId,

        @Schema(description = "Amount applied to the order and the invoice (the due before rounding)", example = "9.98")
        @NonNull
        BigDecimal amountDue,

        @Schema(
                description = "Cash increment the drawer rounded to; null when the currency does not round",
                example = "0.05")
        @Nullable
        BigDecimal cashIncrement,

        @Schema(description = "Cash the customer pays: amountDue rounded HALF_UP to the increment", example = "10.00")
        @NonNull
        BigDecimal cashDue,

        @Schema(description = "cashDue minus amountDue, signed; positive when the customer paid more", example = "0.02")
        @NonNull
        BigDecimal cashRoundingAmount,

        @Schema(description = "Cash the customer handed over", example = "20.00") @NonNull
        BigDecimal cashTendered,

        @Schema(description = "cashTendered minus cashDue", example = "10.00") @NonNull
        BigDecimal changeDue,

        @Schema(description = "Drawer currency", example = "CAD") @NonNull
        String currencyCode,

        @Schema(description = "Balance left on the order after this tender is applied", example = "0.00") @NonNull
        BigDecimal balanceDue) {}
