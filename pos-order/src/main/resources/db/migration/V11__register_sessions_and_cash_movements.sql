-- Wave 5 (odoo parity #1081/#1082): register sessions & cash management.
-- Odoo reference: pos.session + cash control. A session is a drawer-shift on one terminal;
-- orders tendered while it is OPEN bind to it, and closing reconciles counted vs theoretical cash.

-- G1 (#1081): register session lifecycle (OPEN -> CLOSING -> CLOSED).
CREATE TABLE register_session (
  session_id UUID PRIMARY KEY,
  version BIGINT NOT NULL,
  terminal_id VARCHAR(255) NOT NULL,
  location_id UUID,
  opened_by_clerk_id VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  opening_float NUMERIC(19, 4) NOT NULL,
  -- Counted (physical) closing cash, entered at begin-close; theoretical is computed on demand.
  counted_cash NUMERIC(19, 4),
  theoretical_cash NUMERIC(19, 4),
  -- counted - theoretical, persisted at close for reporting/GL (positive over, negative short).
  over_short NUMERIC(19, 4),
  variance_approved BOOLEAN NOT NULL DEFAULT FALSE,
  variance_approved_by VARCHAR(255),
  closed_by_clerk_id VARCHAR(255),
  opened_at TIMESTAMPTZ NOT NULL,
  closing_started_at TIMESTAMPTZ,
  closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

-- One OPEN session per terminal (R6.1): a partial unique index lets closed history accumulate.
CREATE UNIQUE INDEX ux_register_session_one_open_per_terminal
  ON register_session (terminal_id)
  WHERE status = 'OPEN';

CREATE INDEX ix_register_session_terminal ON register_session (terminal_id);

-- G1 (#1081): drawer cash movements not tied to a sale (Odoo try_cash_in_out analog).
CREATE TABLE cash_movement (
  movement_id UUID PRIMARY KEY,
  session_id UUID NOT NULL,
  movement_type VARCHAR(16) NOT NULL,
  amount NUMERIC(19, 4) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  clerk_id VARCHAR(255) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_cash_movement_session FOREIGN KEY (session_id)
    REFERENCES register_session (session_id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_movement_session ON cash_movement (session_id);

-- G1 (#1081): orders created while a session is open carry its id; the session supplies the
-- locationId fallback and its id rides OrderCompletedV1 for downstream reconciliation.
ALTER TABLE sales_order ADD COLUMN session_id UUID;
CREATE INDEX ix_sales_order_session ON sales_order (session_id);
