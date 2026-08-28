package com.positivity.order.internal.service.model;

import java.util.UUID;

public record CancellationResult(UUID orderId, String status, String message, String cancellationIdempotencyKey) {}
