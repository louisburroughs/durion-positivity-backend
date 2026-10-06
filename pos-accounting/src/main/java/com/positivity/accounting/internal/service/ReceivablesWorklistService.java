package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CustomerOpenInvoicesPage;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The two reads a clerk matches customer payments with (#2502; spec §7.1 "Unapplied payments" and
 * "Eligible invoices"): the payments still waiting to be matched, each with its suggestion, and a
 * customer's open invoices with the derived balance due. Both are read-only (BR-5); the tenant comes
 * from the security context and row-level security (BR-6).
 */
public interface ReceivablesWorklistService {

    /**
     * A page of {@code AVAILABLE} payments, oldest cleared first (ties by payment id), each with its
     * suggestion, and totals over every payment matching the filter.
     *
     * @param customerId only this customer's payments, when given
     * @param page       page index, 0 or more
     * @param size       page size, 1 to 100
     */
    @NonNull
    UnappliedPaymentsPage listUnappliedPayments(@Nullable UUID customerId, int page, int size);

    /**
     * A page of the customer's open invoices in {@code OLDEST_FIRST} order, and totals over all of
     * them. An unknown customer and a customer with nothing open both get an empty page.
     *
     * @param customerId the customer
     * @param page       page index, 0 or more
     * @param size       page size, 1 to 200
     */
    @NonNull
    CustomerOpenInvoicesPage listOpenInvoices(@NonNull UUID customerId, int page, int size);
}
