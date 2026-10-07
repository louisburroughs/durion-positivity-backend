package com.positivity.accounting.internal.bankrec.readmodel;

import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which days a bank account's committed statements already cover, for callers outside the bank reconciliation
 * core that may reach it only through its read model (the bank opening balance, #2572, OI-10).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankStatementCoverage {

    private final BankStatementRepository statements;

    /** Whether a COMMITTED statement on the account starts on or before {@code date}. */
    public boolean startsOnOrBefore(@NonNull UUID glAccountId, @NonNull LocalDate date) {
        return statements.existsByGlAccountIdAndStatusAndStartDateLessThanEqual(
                glAccountId, BankStatementStatus.COMMITTED, date);
    }
}
