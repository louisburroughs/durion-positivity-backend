package com.positivity.order.internal.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment reversal instruction for the cancellation saga (spec R4.6). {@code type} is VOID for a
 * never-captured authorization or REFUND for settled funds; {@code amount} is the net settled
 * amount to return (ignored for voids).
 */
public record ReversePaymentCommand(
        String type, BigDecimal amount, String currency, String reasonCode, UUID orderId, String idempotencyKey) {}
