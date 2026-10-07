package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.RegisterFloatChangeRequest;
import com.positivity.accounting.internal.dto.RegisterFloatGoLiveRequest;
import com.positivity.accounting.internal.dto.RegisterFloatRelocationRequest;
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
     *     FLOAT_AMOUNT_UNCHANGED}, 422 {@code FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE}, 422 {@code
     *     FLOAT_DATE_BEFORE_RELOCATION}, 409 {@code IDEMPOTENCY_CONFLICT}
     */
    @NonNull
    Outcome changeFloat(@NonNull String registerId, @NonNull RegisterFloatChangeRequest request);

    /**
     * Moves the register, and its float, from {@code fromLocationId} to {@code toLocationId} (#2571, AW32): Dr
     * 1080 {register, to} / Cr 1080 {register, from} for the current float, dated the effective date; a zero
     * float posts nothing. The float is unchanged. Never reversed: a wrong move is corrected by moving again.
     *
     * @throws com.positivity.accounting.internal.exception.CashSetupException 404 {@code
     *     FLOAT_REGISTER_NOT_FOUND}, 422 {@code FLOAT_REGISTER_LOCATION_MISMATCH}, 422 {@code
     *     FLOAT_RELOCATION_SAME_LOCATION}, 422 {@code FLOAT_REGISTER_SESSION_OPEN} (#2573: the register's
     *     latest-opened pos-order session is open), 422 {@code FLOAT_RELOCATION_DATE_INVALID}, 422 {@code
     *     FLOAT_AMOUNT_NEGATIVE}, 409 {@code IDEMPOTENCY_CONFLICT}
     */
    @NonNull
    Outcome relocate(@NonNull String registerId, @NonNull RegisterFloatRelocationRequest request);
}
