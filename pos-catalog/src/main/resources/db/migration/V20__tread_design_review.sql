-- #1645 (CAP-324 follow-on to #1352): enrichment matching v2 — confidence tiers, ambiguity
-- parking and a review/resolve trail.
--
-- #1352 attached a design to every product scoring above a single threshold, last write wins, with
-- no record of why and no way for a person to disagree. Three things change here, and none of them
-- touch what a product IS: a design now carries a review state, the candidates it scored are kept
-- so a reviewer can see what the machine saw, and an attachment records whether a person or the
-- matcher made it — which is the only thing that lets an automatic pass know to leave a human
-- decision alone.

-- ── Review state on the design ────────────────────────────────────────────────────────────────
-- Nullable first, backfilled, then made NOT NULL: existing rows predate the column and the state
-- they belong in is derivable (below), so no deployment has to guess a default.
ALTER TABLE tread_design ADD COLUMN match_state character varying(20);
ALTER TABLE tread_design ADD COLUMN match_state_at timestamp(6) with time zone;
ALTER TABLE tread_design ADD COLUMN resolved_by character varying(200);
ALTER TABLE tread_design ADD COLUMN resolution_note text;
-- DEFERRED with a date a reviewer chose: "ask me again after the vendor's next publication".
ALTER TABLE tread_design ADD COLUMN defer_until timestamp(6) with time zone;

-- Backfill: a design a product already points at was matched; everything else has simply not
-- matched yet. REVIEW/REJECTED/DEFERRED cannot be inferred — no one has reviewed anything yet.
UPDATE tread_design td
SET match_state = CASE
        WHEN EXISTS (SELECT 1 FROM product p WHERE p.tread_design_id = td.id) THEN 'MATCHED'
        ELSE 'UNMATCHED'
    END,
    match_state_at = td.updated_at;

ALTER TABLE tread_design ALTER COLUMN match_state SET NOT NULL;
ALTER TABLE tread_design ALTER COLUMN match_state_at SET NOT NULL;

CREATE INDEX idx_tread_design_match_state ON tread_design (match_state);

-- ── What the matcher saw ──────────────────────────────────────────────────────────────────────
-- Kept per design so a reviewer can judge a near miss instead of re-running the matcher in their
-- head, and so the ambiguity rule (one product claimed at AUTO tier by two designs) can be
-- evaluated against other designs' scores rather than only this pass's.
--
-- Both foreign keys cascade: a candidate row is an observation about a (design, product) pair and
-- means nothing once either side is gone. That is the opposite of product.tread_design_id, which
-- is SET NULL because a product must survive its design.
CREATE TABLE tread_design_match_candidate (
    id uuid NOT NULL,
    tread_design_id uuid NOT NULL,
    product_id uuid NOT NULL,

    -- 0.0000-1.0000; four decimals so a threshold comparison is exact rather than a float compare.
    score numeric(5, 4) NOT NULL,
    tier character varying(20) NOT NULL,

    created_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_tread_design_match_candidate PRIMARY KEY (id),
    CONSTRAINT uk_tread_design_match_candidate_design_product UNIQUE (tread_design_id, product_id),
    CONSTRAINT fk_tread_design_match_candidate_design
        FOREIGN KEY (tread_design_id) REFERENCES tread_design (id) ON DELETE CASCADE,
    CONSTRAINT fk_tread_design_match_candidate_product
        FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
);

CREATE INDEX idx_tread_design_match_candidate_product ON tread_design_match_candidate (product_id);

-- ── Who attached this product ─────────────────────────────────────────────────────────────────
-- AUTO = the matcher decided; MANUAL = a person did, through the resolve endpoint. Null exactly
-- when tread_design_id is null. This column exists so an automatic pass can tell a decision it is
-- allowed to revise from one it is not: a MANUAL attachment is never re-pointed by matching.
ALTER TABLE product ADD COLUMN tread_design_source character varying(10);

-- Every attachment that exists today was made by the #1352 matcher, by construction: there was no
-- way for a person to make one.
UPDATE product SET tread_design_source = 'AUTO' WHERE tread_design_id IS NOT NULL;

-- Backfill above guarantees the invariant before this constraint is added: tread_design_source is
-- set exactly where tread_design_id is not null, and null everywhere else.
ALTER TABLE product ADD CONSTRAINT ck_product_tread_design_source_paired
    CHECK ((tread_design_id IS NULL) = (tread_design_source IS NULL));
