package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.RegisterFloatChangeRequest;
import com.positivity.accounting.internal.dto.RegisterFloatGoLiveRequest;
import com.positivity.accounting.internal.dto.RegisterFloatResponse;
import org.jspecify.annotations.NonNull;

/**
 * A register's change float (#2511; SPEC-accounting-workspace §4.6 "Float", §7.1 "Float"; AW16, AW17).
 */
public interface RegisterFloatService {

    /** The outcome of a command: the response, and whether it answers a replayed requestId. */
    record Outcome(@NonNull RegisterFloatResponse response, boolean replayed) {}

    /**
     * Establishes the register's go-live float: Dr 1080 / Cr 3900 dated {@code goLiveDate}, which must fall
     * in an open period (no override). Once per register.
     *
     * @throws com.positivity.accounting.internal.exception.CashSetupException 409 {@code
     *     FLOAT_ALREADY_ESTABLISHED} when the register has a standing go-live or any standing float change;
     *     409 {@code IDEMPOTENCY_CONFLICT} when the requestId was used with another body
     */
    @NonNull
    Outcome establishGoLive(@NonNull String registerId, @NonNull RegisterFloatGoLiveRequest request);

    /**
     * Sets the register's float to {@code amount}; the difference posts against the bank account
     * (increase: Dr 1080 / Cr bank; decrease: Dr bank / Cr 1080). A register without a float starts at 0.
     *
     * @throws com.positivity.accounting.internal.exception.CashSetupException 422 {@code
     *     FLOAT_AMOUNT_UNCHANGED}, 422 {@code FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE}, 409 {@code IDEMPOTENCY_CONFLICT}
     */
    @NonNull
    Outcome changeFloat(@NonNull String registerId, @NonNull RegisterFloatChangeRequest request);
}
