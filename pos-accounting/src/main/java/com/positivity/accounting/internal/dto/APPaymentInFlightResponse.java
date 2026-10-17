package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.APPaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One AP payment whose charge the gateway has not settled yet (#2642 ruling 4). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An AP payment in flight: accepted by the gateway, not yet settled")
public class APPaymentInFlightResponse {

    @Schema(description = "Payment UUID")
    private UUID paymentId;

    @Schema(description = "Payment reference", example = "PAY-2026-0412")
    private String paymentRef;

    @Schema(description = "Vendor UUID")
    private UUID vendorId;

    @Schema(description = "Vendor display name at payment time", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String vendorName;

    @Schema(description = "Gross amount", example = "412.00")
    private BigDecimal grossAmount;

    @Schema(description = "Currency", example = "USD")
    private String currency;

    @Schema(description = "The payment's business date (the instruction's acceptance)", example = "2026-10-08")
    private LocalDate paymentDate;

    @Schema(description = "Payment status", example = "GL_POSTED")
    private APPaymentStatus status;

    @Schema(description = "Days since the payment date on today's business date", example = "9")
    private long ageDays;

    @Schema(
            description = "True once the payment has been in flight longer than accounting.ap.settlement.in-flight-warning"
                    + " (default P7D): the operator checks with the bank or the processor",
            example = "true")
    private boolean aged;

    @Schema(description = "When the settlement job last re-read the gateway", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant settlementCheckedAt;
}
