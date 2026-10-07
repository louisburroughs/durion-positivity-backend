package com.positivity.order.internal.entity;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The fixed reasons a drawer cash movement may carry (CAP:550 S16, #2512; SPEC-accounting-workspace
 * §4.6 "Movement reasons", AW15). There is no free text, no "Other" and no customer refund — cash
 * refunds go through the refund flow (AD-001). Each reason fixes the movement's direction and the
 * session-policy type its limit and switch come from.
 */
public enum CashMovementReason {
    /** A small shop expense paid from the drawer against an accounting petty-expense category. */
    PETTY_EXPENSE(CashMovementType.PAID_OUT, SessionPolicyType.PETTY_EXPENSE),
    /** A vendor paid in cash on delivery. */
    VENDOR_COD(CashMovementType.PAID_OUT, SessionPolicyType.VENDOR_COD),
    /** Cash taken from the drawer for the bank in a numbered deposit bag. */
    BANK_DROP(CashMovementType.PAID_OUT, SessionPolicyType.BANK_DROP),
    /** Cash added to bring the drawer up to a raised configured float. */
    FLOAT_INCREASE(CashMovementType.PAID_IN, SessionPolicyType.FLOAT_CHANGE),
    /** Cash removed to bring the drawer down to a lowered configured float. */
    FLOAT_DECREASE(CashMovementType.PAID_OUT, SessionPolicyType.FLOAT_CHANGE);

    private final CashMovementType direction;
    private final SessionPolicyType policyType;

    CashMovementReason(CashMovementType direction, SessionPolicyType policyType) {
        this.direction = direction;
        this.policyType = policyType;
    }

    /** The stored movement type: PAID_IN for cash into the drawer, PAID_OUT for cash out. */
    public @NonNull CashMovementType direction() {
        return direction;
    }

    /** The session-policy row whose switch and limit apply to this reason. */
    public @NonNull SessionPolicyType policyType() {
        return policyType;
    }

    /** True for the two float reasons, which must match an accounting float change. */
    public boolean isFloatChange() {
        return policyType == SessionPolicyType.FLOAT_CHANGE;
    }

    /** The reason named by {@code value} (case-insensitive), or empty when it names none. */
    public static @NonNull Optional<CashMovementReason> parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
