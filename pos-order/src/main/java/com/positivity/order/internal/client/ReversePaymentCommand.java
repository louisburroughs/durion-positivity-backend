package com.positivity.order.internal.client;

import java.util.UUID;

public record ReversePaymentCommand(String type, String currency, String reasonCode, UUID orderId,
        String idempotencyKey) {
}