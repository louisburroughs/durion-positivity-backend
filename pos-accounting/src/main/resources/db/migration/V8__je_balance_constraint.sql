-- Story A1 (issue #935): DB-level journal-entry balance enforcement.
--
-- Storage backstop for the service-layer balance check in
-- JournalEntryServiceImpl.validateBalance (tolerance +/- 0.0001, JE_NOT_BALANCED 422).
-- The service check stays authoritative for friendly API errors; these constraints
-- guarantee that no code path (including direct SQL) can persist an unbalanced
-- POSTED entry or a line that is both debit and credit.
--
-- 1) Per-line CHECK: amounts are unsigned debit/credit columns (see
--    JournalEntryLine.debitAmount/creditAmount, both NOT NULL default 0). A line
--    posts either a debit or a credit, never both. Legacy zero/zero rows stay legal.
--    Added NOT VALID: enforced for new and updated rows only, so pre-existing
--    dirty rows in an already-populated schema cannot brick this migration.
--
-- 2) Deferred CONSTRAINT TRIGGER on journal_entry_line (INSERT/UPDATE/DELETE):
--    when the parent entry is POSTED, abs(sum(debit) - sum(credit)) must be
--    <= 0.0001 at commit time. DRAFT/PENDING/REVERSED entries are exempt (drafts
--    may be transiently unbalanced while edited).
--
-- 3) Deferred CONSTRAINT TRIGGER on journal_entry (UPDATE OF status): flipping an
--    entry to POSTED re-checks the sum even when no line rows change in the
--    transaction.
--
-- Both triggers are DEFERRABLE INITIALLY DEFERRED so multi-statement rebalancing
-- within one transaction is checked once, at commit.

-- NOT VALID: the CHECK guards go-forward writes (new/updated rows) without
-- validating legacy rows at apply time — a dirty alpha/prod row would otherwise
-- fail the ALTER and abort the whole migration. A follow-up
-- `ALTER TABLE journal_entry_line VALIDATE CONSTRAINT
-- chk_journal_entry_line_debit_xor_credit;` should run after a data audit.
-- That validation is deliberately NOT part of this migration: Postgres
-- transactional DDL would roll the entire migration back on dirty data,
-- defeating the purpose of the go-forward guard.
ALTER TABLE journal_entry_line
    ADD CONSTRAINT chk_journal_entry_line_debit_xor_credit
    CHECK (
        debit_amount >= 0
        AND credit_amount >= 0
        AND NOT (debit_amount > 0 AND credit_amount > 0)
    )
    NOT VALID;

-- Shared assertion: raise if the given entry is POSTED and its lines do not balance
-- within the 0.0001 tolerance used by the service layer.
CREATE OR REPLACE FUNCTION assert_journal_entry_balanced(p_journal_entry_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_status    journal_entry.status%TYPE;
    v_imbalance numeric(19, 4);
BEGIN
    SELECT status
      INTO v_status
      FROM journal_entry
     WHERE journal_entry_id = p_journal_entry_id;

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

-- Row trigger for journal_entry_line changes. AFTER constraint triggers ignore the
-- return value; NULL is returned by convention.
CREATE OR REPLACE FUNCTION trg_journal_entry_line_balance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_journal_entry_balanced(OLD.journal_entry_id);
    ELSE
        PERFORM assert_journal_entry_balanced(NEW.journal_entry_id);
        IF TG_OP = 'UPDATE' AND NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id THEN
            -- Line moved between entries: re-check the entry it left as well.
            PERFORM assert_journal_entry_balanced(OLD.journal_entry_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_journal_entry_line_balance
    AFTER INSERT OR UPDATE OR DELETE ON journal_entry_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION trg_journal_entry_line_balance();

-- Status flips to POSTED must re-check even when no line rows change in the
-- transaction (e.g. UPDATE journal_entry SET status = 'POSTED' on a draft whose
-- lines were persisted unbalanced in an earlier transaction).
CREATE OR REPLACE FUNCTION trg_journal_entry_post_balance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'POSTED' THEN
        PERFORM assert_journal_entry_balanced(NEW.journal_entry_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_journal_entry_post_balance
    AFTER UPDATE OF status ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION trg_journal_entry_post_balance();
