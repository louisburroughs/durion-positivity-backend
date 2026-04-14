package com.positivity.order.internal.client;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

public record SourceDocumentLine(
        @NonNull String itemSku,
        @NonNull String itemDescription,
        int quantity,
        @NonNull BigDecimal unitPrice,
        @NonNull String sourceLineId) {}
