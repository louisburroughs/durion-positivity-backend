package com.positivity.order.internal.client;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One importable source-document line (parity story E1, spec R7.1): priced-as-approved — the
 * unit price is contractual and never repriced. {@code returnable} is the explicit
 * settled-line flag from the source document (resolved Q6); null means "not marked" and imported
 * lines without it are not counter-returnable.
 */
public record SourceDocumentLine(
        @NonNull String itemSku,
        @NonNull String itemDescription,
        int quantity,
        @NonNull BigDecimal unitPrice,
        @NonNull String sourceLineId,
        @Nullable Boolean returnable) {}
