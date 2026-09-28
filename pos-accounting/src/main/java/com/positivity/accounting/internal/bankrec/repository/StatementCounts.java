package com.positivity.accounting.internal.bankrec.repository;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** A statement's transaction counts, grouped by statement id (the statement reads, §6.3; #2301). */
public record StatementCounts(@NonNull UUID statementId, long bankTransactionCount, long possibleDuplicateCount) {}
