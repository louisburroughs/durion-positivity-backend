package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The front door's checks shared by the tax-registration writes (CAP:550 S32c; AW59). */
final class TaxRegistrationRequests {

    private TaxRegistrationRequests() {}

    /**
     * A justification of {@value CashRequests#MIN_JUSTIFICATION} to {@value CashRequests#MAX_JUSTIFICATION}
     * characters and a request id.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR}) naming the field
     */
    static void requireJustificationAndRequestId(@Nullable String justification, @Nullable UUID requestId) {
        if (justification == null
                || justification.trim().length() < CashRequests.MIN_JUSTIFICATION
                || justification.length() > CashRequests.MAX_JUSTIFICATION) {
            throw InvalidRequestParameterException.forField(
                    "justification",
                    "justification is required and must be " + CashRequests.MIN_JUSTIFICATION + " to "
                            + CashRequests.MAX_JUSTIFICATION + " characters");
        }
        if (requestId == null) {
            throw InvalidRequestParameterException.forField("requestId", "requestId is required");
        }
    }
}
