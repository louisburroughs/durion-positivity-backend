package com.positivity.order.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * A drawer cash movement or its manager approval refused by a drawer rule (CAP:550 S16, #2512;
 * SPEC-accounting-workspace §4.6, AW15, AW19, AW31). The {@link Refusal} fixes the error code and the
 * HTTP status; the message never names another location or echoes a credential.
 */
public class CashMovementRefusedException extends RuntimeException {

    /** Each refusal's wire code and status. */
    public enum Refusal {
        /** Above the cashier limit (or a float change) without an approval token. */
        APPROVAL_REQUIRED("CASH_MOVEMENT_APPROVAL_REQUIRED", 403),
        /** The token is unknown, used, expired, or bound to another session, reason, amount, category or vendor. */
        APPROVAL_INVALID("CASH_MOVEMENT_APPROVAL_INVALID", 403),
        /** The approver is the cashier. */
        SELF_APPROVAL("CASH_MOVEMENT_SELF_APPROVAL", 403),
        /**
         * The caller's sign-in carries no user id, so an approver cannot be proven to be someone else:
         * an approval is refused rather than risk a self-approval.
         */
        CALLER_UNIDENTIFIED("CASH_MOVEMENT_CALLER_UNIDENTIFIED", 403),
        /**
         * The step-up could not verify a holder of order:session:approve_cash_movement whose location
         * scope reaches the drawer, or the drawer had too many failed approvals — one code for every reason.
         */
        APPROVAL_DENIED("CASH_MOVEMENT_APPROVAL_DENIED", 403),
        /** The reason's type is switched off in the tenant's drawer policy. */
        TYPE_NOT_ALLOWED("CASH_MOVEMENT_TYPE_NOT_ALLOWED", 422),
        /** The category is not an ACTIVE category of pos-order's copy. */
        CATEGORY_UNKNOWN("PETTY_EXPENSE_CATEGORY_UNKNOWN", 422),
        /** A float movement that does not match a recorded accounting float change. */
        FLOAT_CHANGE_NOT_RECORDED("FLOAT_CHANGE_NOT_RECORDED", 422);

        private final String code;
        private final int status;

        Refusal(String code, int status) {
            this.code = code;
            this.status = status;
        }

        public @NonNull String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    private final transient Refusal refusal;

    public CashMovementRefusedException(@NonNull Refusal refusal, @NonNull String message) {
        super(message);
        this.refusal = refusal;
    }

    public @NonNull Refusal refusal() {
        return refusal;
    }
}
