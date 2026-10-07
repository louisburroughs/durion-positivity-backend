package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Replace the tenant's drawer policy (CAP:550 S16, #2512): the two configurable types, the over/short
 * tolerance, the currency they are stated in, the justification every change carries, and the version
 * the client read (null only while no policy is stored). Validated by the service.
 */
public record UpdateSessionPolicyCommand(
        @Nullable Long expectedVersion,
        @Nullable String currencyCode,
        @Nullable Boolean pettyExpenseAllowed,
        @Nullable BigDecimal pettyExpenseLimit,
        @Nullable Boolean vendorCodAllowed,
        @Nullable BigDecimal vendorCodLimit,
        @Nullable BigDecimal overShortTolerance,
        @Nullable String justification) {}
