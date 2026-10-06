package com.positivity.accounting.internal.repository;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * One term of the invoice balance formula, summed per invoice by a grouped query: the batch form of
 * {@code InvoiceBalanceCalculator#balancesDue} reads a page of invoices with one query per term
 * (#2502), never one per invoice.
 */
public record InvoiceAmount(
        @NonNull UUID invoiceId, @NonNull BigDecimal amount) {}
