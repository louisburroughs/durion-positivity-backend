package com.positivity.accounting.internal.repository;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

/** Count and unapplied total over every receivable payment matching a filter (#2502). */
public record ReceivablePaymentTotals(long count, @NonNull BigDecimal totalUnapplied) {}
