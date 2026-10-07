package com.positivity.accounting.internal.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;

/**
 * A refusal of a vendor-bill command (#2509; SPEC-accounting-workspace §4.3, §7.1; AW37-AW43): one stable code, one
 * HTTP status (ADR-0017 "one condition, one status"), a message in business words that names the bill by its number
 * and never echoes another tenant's identifier.
 */
public class VendorBillException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The codes and their statuses (S34 lists them in ERROR_CODES.md). */
    public enum Code {
        /** No bill with that id is visible to the caller. */
        VENDOR_BILL_NOT_FOUND(HttpStatus.NOT_FOUND),
        /** No match candidate with that id is visible to the caller. */
        AP_MATCH_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND),
        /** The bill's own status does not allow the transition; the message names the status. */
        AP_BILL_NOT_APPROVABLE(HttpStatus.CONFLICT),
        /** An approved bill with an allocation cannot be voided; a vendor credit note corrects it (AW42). */
        AP_BILL_NOT_VOIDABLE(HttpStatus.CONFLICT),
        /** Someone else already resolved the ambiguous match. */
        AP_MATCH_CANDIDATE_ALREADY_RESOLVED(HttpStatus.CONFLICT),
        /** No class for the bill (or its non-stock lines) and no vendor default (AW39). */
        AP_BILL_UNCLASSIFIED(HttpStatus.UNPROCESSABLE_CONTENT),
        /** A justification or reason absent, blank or under 10 characters. */
        JUSTIFICATION_REQUIRED(HttpStatus.BAD_REQUEST),
        /** A request field outside its contract (an unknown action, a class the document cannot take). */
        VALIDATION_ERROR(HttpStatus.BAD_REQUEST);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public @NonNull HttpStatus status() {
            return status;
        }
    }

    private final Code code;

    public VendorBillException(@NonNull Code code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public @NonNull Code getCode() {
        return code;
    }
}
