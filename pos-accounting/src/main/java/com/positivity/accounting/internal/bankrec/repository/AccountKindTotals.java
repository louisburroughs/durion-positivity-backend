package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** A count and a signed amount (debit minus credit) of one outstanding-item kind, grouped by bank account (#2515). */
public record AccountKindTotals(
        @NonNull UUID glAccountId, @NonNull OutstandingItemKind kind, long count, @NonNull BigDecimal signedAmount) {}
