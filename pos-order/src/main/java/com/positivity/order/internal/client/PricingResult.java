package com.positivity.order.internal.client;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

public record PricingResult(@NonNull BigDecimal price, boolean stale, boolean found) {}
