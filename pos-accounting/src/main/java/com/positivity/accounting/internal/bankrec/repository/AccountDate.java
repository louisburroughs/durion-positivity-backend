package com.positivity.accounting.internal.bankrec.repository;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A date grouped by bank account (the bank-account list, §6.1; #2301). */
public record AccountDate(
        @NonNull UUID glAccountId, @Nullable LocalDate date) {}
