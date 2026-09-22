package com.positivity.workorder.internal.exception;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A create lost a race for its estimate or workorder number: the insert failed the number's
 * unique constraint (#2150).
 *
 * <p>{@code DocumentNumberAllocator} serializes allocation, so this should not happen; it covers
 * what the counter cannot see, such as two workorders inheriting the same estimate number across
 * a year boundary. Answered as {@code 409 DOCUMENT_NUMBER_CONFLICT} with {@code Retry-After}: the
 * request was valid and the same body succeeds when re-sent, because a retry draws a new number.
 * Before this, the violation escaped as an unmapped {@code 500 INTERNAL_ERROR}.
 */
public class DocumentNumberConflictException extends RuntimeException {

    public static final String ERROR_CODE = "DOCUMENT_NUMBER_CONFLICT";

    /** Unique constraint on {@code (tenant_id, location_id, estimate_number)}. */
    public static final String ESTIMATE_NUMBER_CONSTRAINT = "estimate_location_id_estimate_number_key";

    /** Unique index on {@code (tenant_id, workorder_number)}. */
    public static final String WORKORDER_NUMBER_CONSTRAINT = "ux_workorder_workorder_number";

    public DocumentNumberConflictException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }

    /** Whether {@code ex} is a violation of the named unique constraint, anywhere in its cause chain. */
    public static boolean isViolationOf(@NonNull DataIntegrityViolationException ex, @NonNull String constraintName) {
        String wanted = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(wanted)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
