-- PR #970 review F4: close a race in the V8 balance assertion between
-- concurrent writers of the same journal entry.
--
-- Race: assert_journal_entry_balanced (V8) read the parent journal_entry.status
-- with a plain SELECT and summed journal_entry_line under READ COMMITTED
-- snapshot rules. Two concurrent transactions each deleting one side of a
-- balanced POSTED entry (A deletes the debit line, B deletes the credit line)
-- each ran their deferred commit-time check against a snapshot that still
-- contained the other side's line. Both checks passed, both committed, and an
-- unbalanced POSTED entry persisted.
--
-- Fix: re-create the function (never edit applied V8) identical to V8's
-- version except the status lookup takes a parent-row lock with
-- FOR NO KEY UPDATE. The first committing transaction holds that row lock
-- until commit, so a concurrent checker of the same entry blocks on the
-- SELECT ... FOR NO KEY UPDATE, and once it proceeds it sees the committed
-- line deletions (locking reads see the latest row version) — its own sum
-- then fails the balance check. Serializing concurrent balance checkers on
-- the same entry closes the race; checkers of different entries do not
-- contend. FOR NO KEY UPDATE (not FOR UPDATE) keeps the lock compatible with
-- concurrent KEY SHARE locks taken by foreign-key inserts referencing the
-- entry. Exception message and ERRCODE are unchanged from V8.
CREATE OR REPLACE FUNCTION assert_journal_entry_balanced(p_journal_entry_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_status    journal_entry.status%TYPE;
    v_imbalance numeric(19, 4);
BEGIN
    SELECT status INTO v_status FROM journal_entry WHERE journal_entry_id = p_journal_entry_id FOR NO KEY UPDATE;

    -- Parent deleted in the same transaction, or entry not POSTED: exempt.
    IF v_status IS NULL OR v_status <> 'POSTED' THEN
        RETURN;
    END IF;

    SELECT COALESCE(SUM(debit_amount) - SUM(credit_amount), 0)
      INTO v_imbalance
      FROM journal_entry_line
     WHERE journal_entry_id = p_journal_entry_id;

    IF ABS(v_imbalance) > 0.0001 THEN
        RAISE EXCEPTION
            'journal entry % is POSTED but unbalanced: sum(debit) - sum(credit) = %',
            p_journal_entry_id, v_imbalance
            USING ERRCODE = '23514'; -- check_violation
    END IF;
END;
$$;
