package com.positivity.inventory.internal.service;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.Locale;
import org.jspecify.annotations.NonNull;

/**
 * Shared construction rules for goods-receipt documents, so the goods-receipt endpoint and the
 * receiving-session receipts (#2455) number their documents the same way.
 */
public final class GoodsReceiptFactory {

    private GoodsReceiptFactory() {}

    /** A fresh human-readable receipt number, {@code GR-} plus eight hex characters. */
    public static @NonNull String newReceiptNumber() {
        return "GR-" + UUIDv7Generator.generate().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
