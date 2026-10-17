package com.positivity.order.internal.service;

import com.positivity.order.internal.dto.CashTenderRequest;
import com.positivity.order.internal.dto.CashTenderResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Register cash tender with rounding to the currency's cash increment (ADR-0067 A8, #2662). */
public interface CashTenderService {

    /**
     * Rounds the cash due for {@code orderId} at the register, checks the tender covers it and records the tender
     * through pos-invoice under {@code idempotencyKey}. Nothing is recorded in pos-order before pos-invoice accepts;
     * the order's balance follows from the settled fact.
     */
    @NonNull
    CashTenderResponse recordCashTender(
            @NonNull UUID orderId, @NonNull String idempotencyKey, @NonNull CashTenderRequest request);
}
