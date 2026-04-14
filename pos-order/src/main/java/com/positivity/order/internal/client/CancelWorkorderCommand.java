package com.positivity.order.internal.client;

import java.util.UUID;

public record CancelWorkorderCommand(UUID orderId, String requestedBy, String reasonCode, String idempotencyKey) {}
