package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.Deposit;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Bank deposits of drawer cash (CAP:550 S18, #2514). */
public interface DepositRepository extends JpaRepository<Deposit, UUID> {

    /** The deposit a Record bank deposit requestId already recorded, if any (idempotent replay). */
    @NonNull
    Optional<Deposit> findByRequestId(@NonNull UUID requestId);

    /** The deposit a Reverse deposit requestId already reversed, if any (idempotent replay). */
    @NonNull
    Optional<Deposit> findByReversalRequestId(@NonNull UUID reversalRequestId);

    /** The deposit that owns a journal entry, if one does. */
    @NonNull
    Optional<Deposit> findByJournalEntryId(@NonNull UUID journalEntryId);

    /** The deposit a journal entry reverses, row-locked to the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Deposit d WHERE d.reversalJournalEntryId = :journalEntryId")
    @NonNull
    Optional<Deposit> lockByReversalJournalEntryId(@Param("journalEntryId") @NonNull UUID journalEntryId);

    /** The deposit that owns a journal entry, row-locked to the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Deposit d WHERE d.journalEntryId = :journalEntryId")
    @NonNull
    Optional<Deposit> lockByJournalEntryId(@Param("journalEntryId") @NonNull UUID journalEntryId);
}
