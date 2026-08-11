-- ADR-0050 §7: "payload reads require the dedicated supplier:audit:read permission (tighter than
-- profile admin) and are themselves audited (who read which exchange, when)". That parenthetical is
-- this table's column spec: subject, object, timestamp.
--
-- WHY A SEPARATE TABLE rather than more columns on supplier_exchange_audit:
-- an access row has no attempt, no outcome, no vendor, no protocol and no payload. Folding it in
-- would require every one of those columns to become nullable and would force the
-- chk_saudit_outcome and chk_saudit_metadata_only_has_no_payload CHECK constraints to be loosened --
-- destroying the very guarantees those constraints exist to provide. "What we sent a vendor" and
-- "who looked at it" are different facts with different lifecycles.
--
-- WHY V4 rather than editing V3: V3 is already applied, and scripts/check-flyway-hygiene.sh
-- correctly forbids modifying an applied migration.

CREATE TABLE supplier_audit_access (
    audit_access_id uuid NOT NULL,

    -- The exchange whose payload was read. A PLAIN UUID with NO foreign key, consistent with this
    -- module's established treatment of audit identity (see V3): the access record must survive the
    -- purge or deletion of anything it references, and an FK would let cleanup elsewhere either block
    -- or cascade away evidence of who read what.
    exchange_audit_id uuid NOT NULL,

    -- Denormalised so an access row stays interpretable on its own, without joining a table whose
    -- row may have had its payloads purged. Descriptive only, never a join key.
    vendor_profile_id uuid NOT NULL,
    capability character varying(64) NOT NULL,

    -- Subject: taken from the security context (ADR-0018), never client-supplied and never free text.
    accessed_by character varying(255) NOT NULL,
    accessed_at timestamp(6) with time zone NOT NULL,

    -- What was actually reached, so a metadata listing can never be mistaken for a payload read.
    access_kind character varying(32) NOT NULL,

    -- What the read actually yielded. A failed decrypt is still an ATTEMPT to read commercial
    -- content, which is what §7 cares about, so the row is written either way and this column records
    -- which. NOT_CAPTURED covers the legitimate empty cases -- a METADATA_ONLY binding, a vendor that
    -- sent no body, or a row whose payloads the 400-day purge has already nulled: someone reached for
    -- the content and there was none. Recording that honestly is the point; an audit row that claimed
    -- DECRYPTED when there was nothing to decrypt would be a lie in an evidentiary record.
    payload_outcome character varying(32) NOT NULL,

    -- NO correlation_id column, and that is a decision rather than an oversight. The only correlation
    -- scope this module has is SupplierCorrelationContext, which is established around an OUTBOUND
    -- vendor exchange -- it is not in flight during an inbound audit read, so the column could only ever
    -- have been null. A column that is always null in production is worse than an absent one: it reads
    -- as "this read had no correlation" rather than "we never captured one". Tying an access row to the
    -- request that caused it needs the inbound X-Correlation-Id to be put in scope for every request in
    -- this module, which nothing does today (SupplierCorrelationContext documents the requirement and
    -- only a test exercises it). That is a module-wide request-handling change, deliberately not
    -- smuggled into this slice, and is recorded as a follow-up.

    CONSTRAINT supplier_audit_access_pkey PRIMARY KEY (audit_access_id),
    -- Only actual payload reads are recorded here. A metadata listing is not a payload read, and a
    -- DENIED read is not a read at all -- 403s stay out of this table, or its meaning and its
    -- retention story both blur. Denial visibility belongs to the event mechanism.
    CONSTRAINT chk_saccess_kind CHECK (access_kind IN ('PAYLOAD_READ')),
    CONSTRAINT chk_saccess_payload_outcome
        CHECK (payload_outcome IN ('DECRYPTED', 'UNREADABLE', 'NOT_CAPTURED'))
);

-- "Who read this exchange?" -- the question an investigation actually asks.
CREATE INDEX idx_saccess_exchange ON supplier_audit_access (exchange_audit_id);

-- "What did this person read?" and "what was read in this window?"
CREATE INDEX idx_saccess_actor_time ON supplier_audit_access (accessed_by, accessed_at);
CREATE INDEX idx_saccess_accessed_at ON supplier_audit_access (accessed_at);

-- RETENTION (explicit decision, not an omission): these rows are retained for as long as the
-- exchange-audit metadata rows they reference -- which is permanently. The 400-day payload purge
-- (ADR-0050 §7) deliberately does NOT apply, because this table holds no payloads; it holds the
-- record of who saw them. Purging access records earlier than the metadata they describe would leave
-- exchanges whose read history had silently expired, which is the opposite of §7's intent. Rows are
-- small and bounded by human read activity rather than by traffic volume, so unbounded growth is not
-- a practical concern at this scale; if it ever becomes one, that is a deliberate future decision
-- with a compliance question attached, not a default.
