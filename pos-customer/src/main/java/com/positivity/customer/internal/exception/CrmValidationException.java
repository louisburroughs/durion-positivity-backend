package com.positivity.customer.internal.exception;

/**
 * A malformed request or a field/shape validation failure caught by module-level logic rather
 * than Bean Validation — e.g. an unparsable identifier, or a required-alternative field left
 * empty. Maps to {@code 400} per ADR-0017 §1.
 *
 * <p>Deliberately not an {@link IllegalArgumentException} subtype: that type is also thrown by
 * the JDK and by Hibernate/JPA for server-side defects unrelated to client input, and a
 * blanket {@code @ExceptionHandler(IllegalArgumentException.class)} cannot tell the two apart
 * (issue #1694). Only this module's own genuine input-validation failures should be raised as
 * this type.
 */
public class CrmValidationException extends RuntimeException {

    public CrmValidationException(String message) {
        super(message);
    }

    public CrmValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
