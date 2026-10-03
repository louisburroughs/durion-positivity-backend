package com.positivity.accounting.internal.exception;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import org.jspecify.annotations.NonNull;

/**
 * Thrown by an accounting event processor when an event cannot be processed as received (#2435): it
 * names the status the event should land in ({@code SUSPENDED} when a retry may succeed, {@code
 * FAILED} when it cannot) and the reason code stored on the event. Nothing the processor wrote
 * survives: the drainer rolls the processing transaction back and records the outcome in a fresh one.
 */
public class AccountingEventRejectedException extends RuntimeException {

    private final transient AccountingEventStatus status;
    private final String reasonCode;

    public AccountingEventRejectedException(
            @NonNull AccountingEventStatus status, @NonNull String reasonCode, @NonNull String message) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public @NonNull AccountingEventStatus getStatus() {
        return status;
    }

    public @NonNull String getReasonCode() {
        return reasonCode;
    }
}
