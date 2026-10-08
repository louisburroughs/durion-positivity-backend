package com.positivity.accounting.internal.enums;

/**
 * The class a vendor bill, or a line of it, posts under at approval (AW39; SPEC-accounting-workspace §4.3 "Posting").
 * Accounts always resolve through the {@code VENDOR_BILL} posting category, never by number.
 *
 * <p>A goods-receipt bill classes its own lines: a stocked line matched to its receipt is {@link #RECEIPT_MATCHED},
 * a billed line with no receipt behind it is {@link #GOODS}, and a non-stock line is {@link #EXPENSE}. A bill whose
 * lines are not stored (an EDI bill) takes one class for the whole bill from the approver, {@link #GOODS} or {@link
 * #EXPENSE}; a credit note takes {@link #EXPENSE} or {@link #PRICE_ALLOWANCE}.
 */
public enum VendorBillDebitClass {
    /** A stocked line matched to its receipt: 2100 at the received price, the price difference in 5050. */
    RECEIPT_MATCHED,
    /** Stock not matched to a receipt: 2100 at the billed net; US tax in 5050. */
    GOODS,
    /** Services, supplies, non-stock lines: the {@code VENDOR_BILL} key {@code EXPENSE_<CODE>}, tax included. */
    EXPENSE,
    /** A credit note's price allowance, or a return until Inventory publishes a costed one: 5050. */
    PRICE_ALLOWANCE
}
