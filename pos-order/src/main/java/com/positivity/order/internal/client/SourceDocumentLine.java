package com.positivity.order.internal.client;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

public record SourceDocumentLine(
        @NonNull String itemSku,
        @NonNull String itemDescription,
        int quantity,
        @NonNull BigDecimal unitPrice,
        @NonNull String sourceLineId) {
}
