-- Invoice revenue recognition (issue #1843, ADR-0044 R6).
--
-- pos-accounting consumes invoice.events.v1 (invoice.invoice.updated) and, for a
-- FINALIZED invoice, posts the revenue journal entry
--   Dr 1200 Accounts Receivable (total) / Cr 4000 Service Revenue (total - tax)
--                                        / Cr 2200 Sales Tax Payable (tax)
-- dated at the invoice's finalizedAt. Before this the ext_invoice replica was
-- upserted but nothing ever reached the ledger, so the income statement (which
-- sums posted journal-entry lines) reported $0 revenue.
--
-- 1) invoice_gl_posting — one row per posting cycle (invoice x finalization
--    instant). It is the idempotency ledger for the listener: a redelivered or
--    replayed FINALIZED/POSTED fact for an invoice that already has an open
--    posting (reversal_journal_entry_id IS NULL) posts nothing; a DRAFT/CANCELLED
--    fact for an invoice with an open posting posts the mirror entry and closes
--    the row (reversal_journal_entry_id / reversed_at). An invoice that is
--    finalized, reverted and finalized again carries a new finalized_at and so
--    gets a second row.
--    - uq_invoice_gl_posting_cycle: at most one posting per (invoice, finalized_at)
--      even across reversal, so a late replay of an already-reversed cycle never
--      re-posts it.
--    - uq_invoice_gl_posting_open: at most one OPEN posting per invoice.
--    created_at / updated_at are application-clock timestamps
--    (docs/CLOCK_TIMESTAMP_OWNERSHIP.md), never DEFAULT now().
--
-- 2) kafka_event_outbox — transactional outbox (ADR-0044 §4) for the facts
--    pos-accounting itself publishes onto accounting.events.v1
--    (accounting.invoice.gl-posted). Same shape as pos-invoice's event_outbox
--    (V4 there). Named kafka_event_outbox because this module's pre-existing
--    event_outbox table is the in-process Spring-event outbox drained by
--    OutboxProcessor, which is unrelated and left untouched.
--
-- PostgreSQL-only syntax (partial unique index): the H2 unit-test profile
-- disables Flyway (application-test.yml) and builds the schema from JPA
-- entities, so migration scripts in this module need not be H2-compatible
-- (see V33 / V35 header notes).

CREATE TABLE invoice_gl_posting (
    invoice_gl_posting_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    finalized_at timestamp(6) with time zone NOT NULL,
    journal_entry_id uuid NOT NULL,
    posted_at timestamp(6) with time zone NOT NULL,
    -- Amounts actually posted (revenue = total - tax), so the reversal mirrors the
    -- ledger rather than whatever totals the revert fact happens to carry.
    revenue_amount numeric(19, 4) NOT NULL,
    tax_amount numeric(19, 4) NOT NULL,
    reversal_journal_entry_id uuid,
    reversed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT invoice_gl_posting_pkey PRIMARY KEY (invoice_gl_posting_id),
    CONSTRAINT uq_invoice_gl_posting_cycle UNIQUE (invoice_id, finalized_at),
    CONSTRAINT chk_invoice_gl_posting_reversal_pair
        CHECK ((reversal_journal_entry_id IS NULL) = (reversed_at IS NULL))
);

-- At most one open (un-reversed) posting per invoice.
CREATE UNIQUE INDEX uq_invoice_gl_posting_open
    ON invoice_gl_posting (invoice_id)
    WHERE reversal_journal_entry_id IS NULL;

CREATE INDEX idx_invoice_gl_posting_journal_entry ON invoice_gl_posting (journal_entry_id);

CREATE TABLE kafka_event_outbox (
    id uuid NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    CONSTRAINT kafka_event_outbox_pkey PRIMARY KEY (id)
);

-- Drain scans: unpublished rows in id order (UUIDv7 ids are time-ordered).
CREATE INDEX idx_kafka_event_outbox_unpublished ON kafka_event_outbox (id) WHERE published_at IS NULL;
