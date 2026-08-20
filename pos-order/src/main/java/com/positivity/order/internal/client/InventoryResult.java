package com.positivity.order.internal.client;

import java.math.BigDecimal;

public record InventoryResult(boolean sufficient, BigDecimal availableQuantity) {}
