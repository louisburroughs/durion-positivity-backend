package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response for a single-application reversal (issue #114), exposing the persisted
 * {@code PaymentApplicationReversal} without leaking the JPA entity across the service seam.
 *
 * @param reversalId                    reversal record id
 * @param originalPaymentApplicationId  the application that was reversed
 * @param amount                        amount restored to the payment's unapplied balance
 * @param reason                        audited reversal reason
 * @param reversedAt                    when the reversal was recorded
 * @param reversedBy                    acting user (or {@code SYSTEM})
 */
@Schema(description = "Result of reversing a single payment application")
public record PaymentApplicationReversalResponse(
        UUID reversalId,
        UUID originalPaymentApplicationId,
        BigDecimal amount,
        String reason,
        Instant reversedAt,
        String reversedBy) {}
