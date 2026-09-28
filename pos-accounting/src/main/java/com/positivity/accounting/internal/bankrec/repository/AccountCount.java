package com.positivity.accounting.internal.bankrec.repository;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** A count grouped by bank account (the bank-account list, §6.1; #2301). */
public record AccountCount(@NonNull UUID glAccountId, long count) {}
