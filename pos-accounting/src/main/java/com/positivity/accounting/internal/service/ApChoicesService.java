package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ApPayFromAccountListResponse;
import com.positivity.accounting.internal.dto.VendorBillExpenseCategoryListResponse;
import org.jspecify.annotations.NonNull;

/**
 * The choices the Bills to pay page offers an approver and a payer (AP reads #2670; ruling 6079195896 rows 2, 11, 15):
 * the expense categories and the accounts a payment may come from. The server decides both (P7); the page only shows
 * them. Reads only.
 */
public interface ApChoicesService {

    /**
     * The active {@code VENDOR_BILL} expense keys with the account each resolves to on the tenant's business date.
     *
     * @return the categories by label (case-insensitive, a null label as its key), then key; empty when none
     */
    @NonNull
    VendorBillExpenseCategoryListResponse expenseCategories();

    /**
     * The accounts a vendor payment executed now may come from, by the pay command's own rule ({@link
     * ApPayFromAccounts}), with the default an omitted {@code bankAccountId} resolves to.
     *
     * @return the accounts by account number; empty when none is set up
     */
    @NonNull
    ApPayFromAccountListResponse payFromAccounts();
}
