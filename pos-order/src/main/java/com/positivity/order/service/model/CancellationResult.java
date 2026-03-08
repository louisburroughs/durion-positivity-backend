package com.positivity.order.service.model;

import java.util.UUID;

public record CancellationResult(
        UUID orderId,
        String status,
        String message,
        String cancellationIdempotencyKey) {
}