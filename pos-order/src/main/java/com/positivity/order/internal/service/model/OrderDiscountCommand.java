package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command for applying an order-level discount (parity story B2, spec R3.10).
 *
 * @param type PERCENT or AMOUNT
 * @param value percent (0-100] or currency amount, per type
 * @param reasonCode business reason for the discount
 */
public record OrderDiscountCommand(
        @NonNull String type,
        @NonNull BigDecimal value,
        @Nullable String reasonCode) {

    public OrderDiscountCommand {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
