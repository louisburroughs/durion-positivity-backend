package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.BankOpeningBalance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The bank opening balance commands (#2572, OI-10). */
public interface BankOpeningBalanceRepository extends JpaRepository<BankOpeningBalance, UUID> {

    /** The command a requestId already ran, if any (idempotent replay). */
    @NonNull
    Optional<BankOpeningBalance> findByRequestId(@NonNull UUID requestId);

    /**
     * The account's standing openings: those whose journal entry is still POSTED. A reversed entry is REVERSED
     * (ADR-0047), so its opening no longer stands and the account may be opened again.
     */
    @Query("SELECT b FROM BankOpeningBalance b WHERE b.glAccountId = :glAccountId AND EXISTS (SELECT 1 FROM"
            + " JournalEntry je WHERE je.journalEntryId = b.journalEntryId AND je.status = 'POSTED')")
    @NonNull
    List<BankOpeningBalance> findStandingByGlAccountId(@Param("glAccountId") @NonNull UUID glAccountId);
}
