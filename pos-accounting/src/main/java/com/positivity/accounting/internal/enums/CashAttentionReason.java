package com.positivity.accounting.internal.enums;

/** Why a "Needs attention" item of the cash position stands apart from its kind (CAP:550 S19, #2515). */
public enum CashAttentionReason {
    /**
     * The aged undeposited sessions do not balance: their bank drops differ from the cash their sales put in 1090 plus
     * the 1095 lines their over/short and drawer movements posted, so a deposit would be refused until a controller
     * corrects the postings by reversal.
     */
    UNBALANCED
}
