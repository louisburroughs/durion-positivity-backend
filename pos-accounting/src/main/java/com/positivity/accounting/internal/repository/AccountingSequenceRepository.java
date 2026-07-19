package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingSequence;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for {@link AccountingSequence} counter rows (story A2, issue
 * #942).
 */
public interface AccountingSequenceRepository extends JpaRepository<AccountingSequence, UUID> {

    /**
     * Load a sequence row under a pessimistic write lock
     * ({@code SELECT ... FOR UPDATE}). Concurrent number assigners for the
     * same scope serialize on this lock until the holding transaction
     * commits, which is what makes assigned numbers distinct and consecutive.
     *
     * @param scopeKey sequence scope, e.g. {@code JE-202607}
     * @return the locked row, or empty if the scope has never been used
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountingSequence> findByScopeKey(String scopeKey);

    /**
     * All sequence rows in deterministic scope-key order (no lock). Used by
     * Trial Balance gap-check reporting (story G1, issue #956) to enumerate
     * the monthly {@code JE-&#123;YYYYMM&#125;} scopes to audit.
     */
    List<AccountingSequence> findAllByOrderByScopeKeyAsc();

    /**
     * Gap-detection: expected-vs-stored comparison for one month scope,
     * returning the sequence numbers that were handed out (per
     * {@code next_value}) but have no matching {@code journal_entry.entry_number}
     * row. Intended for G-workstream reporting; nothing invokes it in
     * production yet.
     *
     * <p><strong>PostgreSQL only</strong> ({@code generate_series}, regex
     * {@code substring}) — must not be executed against the H2 dev/test
     * database. Exercised by the Docker-gated concurrency IT and the PG16
     * migration validation only.
     *
     * <p>The stored side parses the numeric suffix of {@code entry_number}
     * server-side (the digits after the last {@code '-'}) rather than
     * re-concatenating expected strings, so it stays correct regardless of
     * any padding convention.
     *
     * @param scopeKey month scope to audit, e.g. {@code JE-202607}
     * @return ascending list of missing sequence numbers; empty when gapless
     */
    @Query(value = """
                    SELECT gs.n
                    FROM accounting_sequence s
                    CROSS JOIN LATERAL generate_series(1, s.next_value - 1) AS gs(n)
                    WHERE s.scope_key = :scopeKey
                      AND NOT EXISTS (
                          SELECT 1
                          FROM journal_entry je
                          WHERE je.entry_number LIKE s.scope_key || '-%'
                            AND (substring(je.entry_number FROM '[0-9]+$'))::bigint = gs.n
                      )
                    ORDER BY gs.n
                    """, nativeQuery = true)
    List<Long> findMissingEntryNumbers(String scopeKey);
}
