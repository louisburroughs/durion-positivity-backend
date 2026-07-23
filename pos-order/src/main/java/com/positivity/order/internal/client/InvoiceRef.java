package com.positivity.order.internal.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Invoice reference returned by the from-order creation call (parity story C1). {@code existing}
 * is true when pos-invoice replayed an already-created invoice (orderId idempotency or workorder
 * dedupe).
 */
public record InvoiceRef(
        @NonNull UUID invoiceId,
        @Nullable String invoiceNumber,
        @NonNull String status,
        @Nullable BigDecimal totalAmount,
        boolean existing) {}
