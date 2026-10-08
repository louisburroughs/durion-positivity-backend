-- CAP:550 S13 (#2510; SPEC-accounting-workspace §4.3, §7.1 "System approver"): who approved a vendor bill.
--
-- approved_by_kind is PERSON or SYSTEM, set on every approval from now on: only a person's approval blocks the same
-- person's payment of the bill (separation of duties 2, AW6), and a system approval never does.
--
-- Backfill (ruling 6 of #2510): an approved row whose approved_by is 'SYSTEM' came from the legacy unconditional
-- automatic approval and is SYSTEM; every other approved row is PERSON. Rows never approved stay null. Flyway runs as
-- the owning superuser, so the backfill reaches every tenant's rows; no timestamp is written (ADR-0024).
--
-- The AP approval policy itself needs no schema: its five settings are accounting_configuration rows, absent until
-- first set (an absent key reads as its default), and its history is the accounting_audit_log.

ALTER TABLE public.vendor_bill ADD COLUMN approved_by_kind character varying(10);

UPDATE public.vendor_bill
   SET approved_by_kind = CASE WHEN approved_by = 'SYSTEM' THEN 'SYSTEM' ELSE 'PERSON' END
 WHERE approved_by IS NOT NULL;

ALTER TABLE public.vendor_bill ADD CONSTRAINT vendor_bill_approved_by_kind_check
    CHECK (approved_by_kind IS NULL OR approved_by_kind IN ('PERSON', 'SYSTEM'));
