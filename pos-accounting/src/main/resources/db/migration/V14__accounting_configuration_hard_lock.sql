-- Story B2 (issue #944): org-level accounting configuration store + hard lock.
--
-- Key/value configuration table for org-level accounting settings. First key
-- (written by the service, not seeded here): HARD_LOCK_DATE — the single
-- org-level hard-lock date (ISO yyyy-MM-dd). No journal entry may ever be
-- posted with a transaction date strictly before it, with no override path;
-- the date only moves forward (monotonic-forward-only, enforced in
-- AccountingConfigurationServiceImpl), which is what makes the lock
-- irreversible.
--
-- The table ships empty: no hard lock exists until an operator sets one.
-- Primary keys are UUID v7 (ADR-0013), generated application-side.

CREATE TABLE accounting_configuration (
    config_id uuid NOT NULL,
    config_key character varying(100) NOT NULL,
    config_value character varying(500) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(50) NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    modified_by character varying(50) NOT NULL,
    CONSTRAINT accounting_configuration_pkey PRIMARY KEY (config_id),
    CONSTRAINT uq_accounting_configuration_key UNIQUE (config_key)
);
