-- CAP-318 / #1224: local replica of pos-catalog product identity codes (ADR-0044 R3).
--
-- PRICAT matching is a cross-domain read, and ADR-0044 R1 forbids the synchronous call that would
-- otherwise answer it. pos-catalog owns these codes and publishes them on catalog.events.v1; this
-- table is this module's read-only copy, written only by the event consumer.
--
-- One row per product: a pos-catalog product carries at most one typed code. A product whose code
-- is cleared keeps its row with NULL code, so "has no code" stays distinguishable from "never seen".
--
-- H2(MODE=PostgreSQL)-compatible, matching V2/V3/V8.
CREATE TABLE ext_product_code (
    product_id uuid NOT NULL,
    code_type character varying(16),
    code character varying(64),
    aggregate_version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_ext_product_code PRIMARY KEY (product_id),
    CONSTRAINT ck_ext_product_code_type CHECK (code_type IS NULL OR code_type IN ('EAN', 'UPC'))
);

-- The matching lookup. Deliberately NOT unique: uniqueness is pos-catalog's invariant (#1232), and
-- a duplicate arriving here is a replication defect that the resolver must be able to see and
-- report as ambiguous rather than have the database reject mid-consumption.
CREATE INDEX idx_ext_product_code_lookup ON ext_product_code (code_type, code);
