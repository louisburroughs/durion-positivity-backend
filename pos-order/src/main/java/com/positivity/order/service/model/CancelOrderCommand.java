package com.positivity.order.service.model;

import java.util.UUID;

public record CancelOrderCommand(String cancellationReason, UUID workOrderId, UUID paymentId, String idempotencyKey) {}
