package com.positivity.accounting.internal.exception;

import java.io.Serial;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A refusal of a vendor-bill command (#2509; SPEC-accounting-workspace §4.3, §7.1; AW37-AW43): one stable code, one
 * HTTP status (ADR-0017 "one condition, one status"), a message in business words that names the bill by its number
 * and never echoes another tenant's identifier. The AP approval policy (CAP:550 S13, #2510) refuses with the same
 * codes. A refusal may carry field errors (one per field or bill named) and a next action.
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
        /**
         * A goods-receipt bill no vendor invoice has been matched to yet cannot be sent, approved or accepted (AW45):
         * match the invoice, select a candidate, or void the bill.
         */
        AP_BILL_AWAITING_INVOICE(HttpStatus.CONFLICT),
        /**
         * A bill's entry, or its void's reversal, reversed outside the void: a bill's ledger follows its status, so
         * only {@code POST /v1/accounting/vendor-bills/{billId}/void} reverses it, and the void is never reversed itself.
         */
        AP_BILL_ENTRY_NOT_REVERSIBLE(HttpStatus.CONFLICT),
        /** Someone else already resolved the ambiguous match. */
        AP_MATCH_CANDIDATE_ALREADY_RESOLVED(HttpStatus.CONFLICT),
        /** No class for the bill (or its non-stock lines) and no vendor default (AW39). */
        AP_BILL_UNCLASSIFIED(HttpStatus.UNPROCESSABLE_CONTENT),
        /**
         * The vendor's gross differs from its net + tax beyond the rounding tolerance and the command says nowhere
         * the difference posts (AW47); nothing is written.
         */
        AP_BILL_TOTALS_UNRECONCILED(HttpStatus.UNPROCESSABLE_CONTENT),
        /**
         * The bill charges tax on goods for resale, its tax country's purchase-tax rule holds such bills, its vendor
         * does not accept the tax and no {@code taxOnResaleOverrideJustification} was given (CAP:550 S43, AW44). 422:
         * a well-formed request refused by a documented rule on an attribute of the bill (ADR-0017 §2); the bill's
         * status is fine. Checked after {@code AP_BILL_UNCLASSIFIED}.
         */
        AP_BILL_TAX_ON_RESALE_GOODS(HttpStatus.UNPROCESSABLE_CONTENT),
        /** A bill totalling 0.00 has nothing to send, approve or post: correct it or void it. */
        AP_BILL_ZERO_TOTAL(HttpStatus.UNPROCESSABLE_CONTENT),
        /**
         * The bill is over the clerk approval limit and the caller holds {@code accounting:ap:approve} but not {@code
         * accounting:ap:approve_over_limit} (CAP:550 S13, #2510; AW4, AW5). 403: it concerns who the caller is
         * (ADR-0017 §2). Checked after the bill's state and before its content.
         */
        AP_APPROVAL_LIMIT_EXCEEDED(HttpStatus.FORBIDDEN),
        /**
         * The caller created the bill and the tenant does not let a creator approve it (separation of duties 1, AW6):
         * approve and {@code ACCEPT}. 403.
         */
        AP_BILL_SELF_APPROVAL(HttpStatus.FORBIDDEN),
        /**
         * The payer approved a bill the payment would pay and the tenant does not let an approver pay (separation of
         * duties 2, AW6); the field errors name the bills by number. 403, before any payment row or gateway call.
         */
        AP_PAYMENT_SELF_APPROVED_BILL(HttpStatus.FORBIDDEN),
        /**
         * An AP payment by {@code CREDIT_CARD} or {@code OTHER}: no funding account is modelled for them yet (OI-17;
         * CAP:550 S42, #2603). 422, the first check of the pay command, before the gateway.
         */
        AP_PAYMENT_METHOD_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT),
        /**
         * {@code gl-posting-retry} of an AP payment that is not {@code GL_POST_FAILED}: already posted, still pending,
         * or never past the gateway (CAP:550 S42, #2603). 409.
         */
        AP_PAYMENT_NOT_RETRYABLE(HttpStatus.CONFLICT),
        /**
         * A new bill or AP payment names an {@code INACTIVE} vendor (CAP:550 S24, #2517; AW23): an inactive vendor takes
         * no new business, and its open bills are not paid while it is inactive. 422.
         */
        VENDOR_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
        /**
         * An AP payment names a vendor on AP hold (CAP:550 #2615): the hold stops money leaving for the vendor, never a
         * bill's approval or posting. 422, slot 1e of the pay command, before any payment row or gateway call. The
         * message names the vendor number only, never the hold reason (ADR-0072).
         */
        VENDOR_ON_AP_HOLD(HttpStatus.UNPROCESSABLE_CONTENT),
        /**
         * A bill the payment would pay was approved at another remit-to version than the vendor's current one, and no
         * one other than the payer confirmed the current version; or a confirmation names a version that is not the
         * current one (CAP:550 S24, #2517, rules 6 and 7). 409, before any payment row or gateway call.
         */
        VENDOR_PAYMENT_DETAILS_CHANGED(HttpStatus.CONFLICT),
        /**
         * The caller requested the vendor's current remit-to in pos-supplier and may not confirm it too (CAP:550 S24,
         * #2517; Accounting ruling on PR #2648, separation of duties). 403, before anything is written.
         */
        VENDOR_REMIT_TO_SELF_CONFIRMATION(HttpStatus.FORBIDDEN),
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

    /** One field error of a refusal: the field, or what is named, and why. */
    public record FieldError(@NonNull String field, @NonNull String message) {}

    private final Code code;
    private final transient List<FieldError> fieldErrors;
    private final @Nullable String nextAction;

    public VendorBillException(@NonNull Code code, @NonNull String message) {
        this(code, message, List.of(), null);
    }

    public VendorBillException(
            @NonNull Code code,
            @NonNull String message,
            @NonNull List<FieldError> fieldErrors,
            @Nullable String nextAction) {
        super(message);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.nextAction = nextAction;
    }

    public @NonNull Code getCode() {
        return code;
    }

    /** The field errors; empty when the refusal names none. */
    public @NonNull List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    /** What the caller can do next; null when there is no single next step. */
    public @Nullable String getNextAction() {
        return nextAction;
    }
}
