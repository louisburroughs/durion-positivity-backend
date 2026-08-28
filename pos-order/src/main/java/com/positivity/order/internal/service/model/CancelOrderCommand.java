package com.positivity.order.internal.service.model;

import java.util.UUID;

/**
 * Cancellation request (spec R4.6): payment reversal is driven by the order's own settlement
 * ledger, so callers no longer supply a payment id.
 */
public record CancelOrderCommand(String cancellationReason, UUID workOrderId, String idempotencyKey) {}
