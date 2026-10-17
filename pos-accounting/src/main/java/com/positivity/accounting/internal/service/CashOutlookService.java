package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CashOutlookResponse;
import org.jspecify.annotations.NonNull;

/**
 * The cash outlook (CAP:550 S19, #2515; SPEC-accounting-workspace §4.2, §5.1 item 5; AW11): a projection of cash from
 * committed items only. Read-only: it posts nothing, stores nothing and never writes a bill's due date.
 */
public interface CashOutlookService {

    /** The default horizon in days. */
    int DEFAULT_DAYS = 30;

    /** The longest horizon in days. */
    int MAX_DAYS = 90;

    /**
     * The outlook for {@code days} days after today on the tenant accounting date.
     *
     * @param days 1 to {@value #MAX_DAYS}
     * @throws com.positivity.accounting.internal.exception.InvalidRequestParameterException {@code VALIDATION_ERROR}
     *     (field {@code days}) outside that range
     */
    @NonNull
    CashOutlookResponse outlook(int days);

    /** {@link #outlook(int)} over cash accounts the caller already read, so one request reads them once. */
    @NonNull
    CashOutlookResponse outlook(int days, CashAccounts.@NonNull Components cash);
}
