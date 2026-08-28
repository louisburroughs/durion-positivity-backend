package com.positivity.invoice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A deposit / down-payment credit and its application-audit trail")
public record DepositCreditResponse(
        UUID depositCreditId,
        String sourceType,
        UUID sourceId,
        UUID orderId,
        String partyId,
        String currencyCode,
        BigDecimal originalAmount,
        BigDecimal remainingBalance,
        String status,
        List<Application> applications,
        Instant createdAt) {

    @Schema(description = "One draw-down of the credit against a settlement invoice")
    public record Application(UUID invoiceId, BigDecimal amountApplied, Instant appliedAt) {}

    public static DepositCreditResponse from(DepositCreditSummary s) {
        return new DepositCreditResponse(
                s.depositCreditId(),
                s.sourceType(),
                s.sourceId(),
                s.orderId(),
                s.partyId(),
                s.currencyCode(),
                s.originalAmount(),
                s.remainingBalance(),
                s.status(),
                s.applications().stream()
                        .map(a -> new Application(a.invoiceId(), a.amountApplied(), a.appliedAt()))
                        .toList(),
                s.createdAt());
    }
}
