-- #2675 (CAP:550 S21 ruling Q4): the petty-expense category history serves what changed as values, not as a sentence
-- the server composed (ADR-0030: the page translates). The columns are filled when the change row is written, with
-- the account name at that moment (the history is the audit trail); rows written before this migration keep only
-- old_value / new_value. DEACTIVATE rows use none of them (their values are the status codes).
ALTER TABLE public.petty_expense_category_change
    ADD COLUMN old_label character varying(100),
    ADD COLUMN new_label character varying(100),
    ADD COLUMN old_examples character varying(500),
    ADD COLUMN new_examples character varying(500),
    ADD COLUMN old_account_number character varying(20),
    ADD COLUMN old_account_name character varying(100),
    ADD COLUMN new_account_number character varying(20),
    ADD COLUMN new_account_name character varying(100),
    ADD COLUMN effective_from date;
