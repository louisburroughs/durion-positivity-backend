package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Replace the tenant's drawer policy (CAP:550 S16, #2512): the two configurable types, the over/short
 * tolerance and the justification every change carries. Validated by the service.
 */
public record UpdateSessionPolicyCommand(
        @Nullable Boolean pettyExpenseAllowed,
        @Nullable BigDecimal pettyExpenseLimit,
        @Nullable Boolean vendorCodAllowed,
        @Nullable BigDecimal vendorCodLimit,
        @Nullable BigDecimal overShortTolerance,
        @Nullable String justification) {}
