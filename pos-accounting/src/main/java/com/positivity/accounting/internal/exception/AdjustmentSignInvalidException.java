package com.positivity.accounting.internal.exception;

import com.positivity.accounting.internal.enums.BankAdjustmentType;

/**
 * Thrown when a bank reconciliation adjustment amount has a sign the adjustment
 * type does not permit (Story F2, issue #965, accounting-owner ruling): a
 * {@code BANK_FEE}/{@code NSF_FEE} may only reduce cash (negative), and
 * {@code INTEREST_EARNED} may only increase it (positive). Maps to 422
 * RECONCILIATION_ADJUSTMENT_SIGN_INVALID.
 */
public class AdjustmentSignInvalidException extends RuntimeException {

    public AdjustmentSignInvalidException(BankAdjustmentType type) {
        super(message(type));
    }

    private static String message(BankAdjustmentType type) {
        String direction =
                switch (type.requiredSign()) {
                    case POSITIVE_ONLY -> "positive (it may only increase cash)";
                    case NEGATIVE_ONLY -> "negative (it may only reduce cash)";
                    case ANY -> "non-zero";
                };
        return "Adjustment amount for type " + type + " must be " + direction;
    }
}
