package com.positivity.workorder.internal.exception;

import java.util.UUID;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A workorder could not be created because the customer's requirements verdict does not permit it
 * (issue #1477).
 *
 * <p>Two conditions reach this exception and a caller must be able to tell them apart, because
 * only one of them is worth retrying:
 *
 * <ul>
 *   <li>{@link #isRetryable()} {@code true} — the {@code ext_customer_party} replica holds no row
 *       for the customer yet. The verdict is owned by pos-customer and arrives asynchronously
 *       (ADR-0044 §6), so a customer created moments ago legitimately has no row and the same
 *       promotion succeeds once the projection catches up. The request is well-formed; the system
 *       is not ready.
 *   <li>{@link #isRetryable()} {@code false} — the replica holds a row and it says requirements
 *       are not met. Retrying cannot change that; something about the customer has to.
 * </ul>
 *
 * <p>Before #1477 both arrived as an {@code IllegalArgumentException} that the promote endpoint
 * answered with a bodiless {@code 400}, leaving callers to retry on the shape of a response
 * rather than on a named condition.
 */
@Getter
public class CustomerRequirementsNotMetException extends RuntimeException {

    /** Error code for the transient case: the verdict is not known here yet. */
    public static final String UNAVAILABLE_CODE = "CUSTOMER_REQUIREMENTS_UNAVAILABLE";

    /** Error code for the permanent case: the verdict is known and negative. */
    public static final String NOT_MET_CODE = "CUSTOMER_REQUIREMENTS_NOT_MET";

    private static final String UNAVAILABLE_NEXT_ACTION =
            "The customer's requirements verdict has not replicated yet. Retry the promotion in a few seconds.";

    private static final String NOT_MET_NEXT_ACTION =
            "Resolve the customer's outstanding requirements in the customer record, then promote again.";

    private final @Nullable UUID customerId;

    private final boolean retryable;

    private CustomerRequirementsNotMetException(@NonNull String message, @Nullable UUID customerId, boolean retryable) {
        super(message);
        this.customerId = customerId;
        this.retryable = retryable;
    }

    /** No replica row for the customer yet: the verdict is unknown here, not negative. */
    public static CustomerRequirementsNotMetException verdictUnavailable(@Nullable UUID customerId) {
        return new CustomerRequirementsNotMetException(
                "Customer requirements verdict is not available yet for customer " + customerId, customerId, true);
    }

    /** The replica holds the customer and its verdict is negative. */
    public static CustomerRequirementsNotMetException requirementsNotMet(@Nullable UUID customerId) {
        return new CustomerRequirementsNotMetException(
                "Customer requirements are not met for customer " + customerId, customerId, false);
    }

    public @NonNull String getErrorCode() {
        return retryable ? UNAVAILABLE_CODE : NOT_MET_CODE;
    }

    public @NonNull String getNextAction() {
        return retryable ? UNAVAILABLE_NEXT_ACTION : NOT_MET_NEXT_ACTION;
    }
}
