package com.positivity.accounting.internal.enums;

/**
 * The decisions a person can take on a vendor bill (#2509; SPEC-accounting-workspace §5.2, P5). The bill read lists,
 * as {@code availableActions}, those valid for the bill's status and whose permission the caller holds; one the
 * tier or the creator rule blocks is listed with {@code allowed = false} and its {@code blockedReason} (S13, #2510).
 */
public enum VendorBillAction {
    /** {@code PENDING_RECEIPT_MATCH | MATCH_EXCEPTION -> AWAITING_APPROVAL}, with a justification. */
    SUBMIT_FOR_APPROVAL,
    /** {@code AWAITING_APPROVAL -> APPROVED}; posts the bill. */
    APPROVE,
    /** {@code AWAITING_APPROVAL -> REJECTED}, with a reason. */
    REJECT,
    /** {@code MATCH_EXCEPTION -> APPROVED} (resolve-exception ACCEPT): an approval; posts the bill. */
    ACCEPT_EXCEPTION,
    /** {@code MATCH_EXCEPTION -> PENDING_RECEIPT_MATCH} (resolve-exception CORRECT): not an approval. */
    CORRECT_EXCEPTION,
    /** {@code MATCH_EXCEPTION -> VOIDED} (resolve-exception VOID), with a reason. */
    VOID_EXCEPTION,
    /** Pick this bill among the candidates of an ambiguous match; it moves to AWAITING_APPROVAL. */
    SELECT_CANDIDATE,
    /** {@code APPROVED -> VOIDED} while nothing is allocated (AW42); reverses the entry on the void date. */
    VOID_APPROVED,
    /**
     * {@code PENDING_RECEIPT_MATCH -> VOIDED} for a goods-receipt bill no invoice will match (AW45), with a reason:
     * posts nothing, the receipt's accrual stays in 2100 for the vendor's EDI bill to clear.
     */
    VOID_UNMATCHED,
    /**
     * Enter the bill's real due date during approval review (CAP:550 S13, #2510; §4.2, AW11): in {@code
     * PENDING_RECEIPT_MATCH}, {@code MATCH_EXCEPTION} or {@code AWAITING_APPROVAL}, for a holder of {@code
     * accounting:ap:approve}. Not tier-gated.
     */
    SET_DUE_DATE
}
