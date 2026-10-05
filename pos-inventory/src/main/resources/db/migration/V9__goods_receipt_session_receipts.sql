-- #2455: a receiving session's receive and cross-dock calls now record a goods_receipt row (one per
-- call), so session receipts appear in goods-receipt reads, putaway and traceability, and carry the
-- idempotency state that makes a retried call a no-op.
--
-- receiving_session_id / receiving_line_id: traceability back to the session and session line the
-- receipt came from. Null on receipts created by POST /v1/inventory/goods-receipts and on any row
-- that predates this column.
--
-- idempotency_scope / idempotency_key / request_fingerprint / response_snapshot: the replay record.
-- The scope names the operation within the session (RECEIVE, or CROSS_DOCK:<lineId>). A call
-- repeating a recorded (session, scope, key) with the same fingerprint returns response_snapshot and
-- posts nothing; a different fingerprint is a 409 IDEMPOTENCY_CONFLICT. All null on receipts that
-- are not session receipts.
ALTER TABLE goods_receipt ADD COLUMN receiving_session_id uuid;
ALTER TABLE goods_receipt ADD COLUMN idempotency_scope varchar(80);
ALTER TABLE goods_receipt ADD COLUMN idempotency_key varchar(255);
ALTER TABLE goods_receipt ADD COLUMN request_fingerprint varchar(64);
ALTER TABLE goods_receipt ADD COLUMN response_snapshot text;
ALTER TABLE goods_receipt ADD COLUMN event_id uuid;

ALTER TABLE goods_receipt_line ADD COLUMN receiving_line_id uuid;

COMMENT ON COLUMN goods_receipt.receiving_session_id IS
    'Receiving session this receipt was recorded from (#2455). Null for receipts not created by a session receive or cross-dock.';
COMMENT ON COLUMN goods_receipt.idempotency_scope IS
    'Operation within the session the idempotency key applies to: RECEIVE, or CROSS_DOCK:<lineId> (#2455).';
COMMENT ON COLUMN goods_receipt.idempotency_key IS
    'Caller-supplied Idempotency-Key, or a server-generated one when the caller sent none (#2455).';
COMMENT ON COLUMN goods_receipt.request_fingerprint IS
    'SHA-256 of the canonical request payload, to tell a replay from a key reused for a different request (#2455).';
COMMENT ON COLUMN goods_receipt.event_id IS
    'Id of the goodsreceipt.recorded event published from this receipt (#2455). Null for receipts that publish under a generated id.';
COMMENT ON COLUMN goods_receipt.response_snapshot IS
    'The JSON response the original call returned, replayed verbatim on a retry (#2455).';
COMMENT ON COLUMN goods_receipt_line.receiving_line_id IS
    'Receiving session line this receipt line was received against (#2455). Null for receipt lines not created by a session.';

ALTER TABLE goods_receipt
    ADD CONSTRAINT goods_receipt_receiving_session_fk
    FOREIGN KEY (tenant_id, receiving_session_id) REFERENCES receiving_session (tenant_id, session_id);

ALTER TABLE goods_receipt_line
    ADD CONSTRAINT goods_receipt_line_receiving_line_fk
    FOREIGN KEY (tenant_id, receiving_line_id) REFERENCES receiving_line (tenant_id, line_id);

CREATE UNIQUE INDEX uq_goods_receipt_session_idempotency
    ON goods_receipt (tenant_id, receiving_session_id, idempotency_scope, idempotency_key)
    WHERE receiving_session_id IS NOT NULL;

CREATE INDEX idx_goods_receipt_receiving_session
    ON goods_receipt (receiving_session_id) WHERE receiving_session_id IS NOT NULL;
