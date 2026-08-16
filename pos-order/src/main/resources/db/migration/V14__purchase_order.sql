-- CAP-320 #1334: the purchase-order aggregate moves to pos-order.
--
-- pos-order becomes the only module that accepts a change to a purchase order (ADR-0049 §2).
-- Columns mirror the pos-inventory table this replaces so existing rows migrate with identity
-- and history intact, minus encumbrance_ref, which #1332 removed as an unimplemented stub.
--
-- Received quantities stay pos-inventory's work: open_quantity_decimal is maintained here, but
-- only in response to a receipt fact pos-inventory publishes. Nothing outside this module writes
-- these tables.

CREATE TABLE purchase_order (
    purchase_order_id       uuid PRIMARY KEY,
    po_number               varchar(255) NOT NULL UNIQUE,
    vendor_id               uuid NOT NULL,
    status                  varchar(255) NOT NULL,
    version_number          integer NOT NULL,
    currency                varchar(255) NOT NULL,
    subtotal_minor          bigint NOT NULL,
    tax_minor               bigint NOT NULL,
    grand_total_minor       bigint NOT NULL,
    open_balance_minor      bigint,
    ship_to_location_id     uuid,
    payment_terms_id        varchar(255),
    po_date                 date,
    expected_delivery_date  date,
    requested_by            varchar(255),
    comment                 varchar(255),
    approver_id             varchar(255),
    approval_timestamp      timestamp(6) with time zone,
    approval_notes          varchar(255),
    created_by              varchar(255) NOT NULL,
    created_at              timestamp(6) with time zone NOT NULL,
    updated_by              varchar(255),
    updated_at              timestamp(6) with time zone NOT NULL,
    CONSTRAINT purchase_order_status_check CHECK (status IN
        ('DRAFT', 'APPROVED', 'PARTIALLY_RECEIVED', 'FULLY_RECEIVED', 'CLOSED', 'CANCELLED'))
);

CREATE TABLE purchase_order_line (
    line_id                 uuid PRIMARY KEY,
    purchase_order_id       uuid NOT NULL REFERENCES purchase_order (purchase_order_id),
    line_number             integer NOT NULL,
    sku_id                  uuid,
    description             varchar(255) NOT NULL,
    quantity_decimal        numeric(18, 6) NOT NULL,
    unit_cost_minor         bigint NOT NULL,
    line_total_minor        bigint NOT NULL,
    tax_minor               bigint,
    tax_code_id             varchar(255),
    gl_account_id           varchar(255),
    open_quantity_decimal   numeric(18, 6),
    document_uom            varchar(32),
    document_quantity       numeric(18, 6),
    conversion_factor       numeric(20, 6),
    created_at              timestamp(6) with time zone NOT NULL
);

-- Supply questions arrive as "what is still open for this SKU, at this site, by this date", so
-- the index carries the SKU rather than only the order it belongs to.
CREATE INDEX idx_purchase_order_line_sku ON purchase_order_line (sku_id);
CREATE INDEX idx_purchase_order_line_order ON purchase_order_line (purchase_order_id);
CREATE INDEX idx_purchase_order_vendor_status ON purchase_order (vendor_id, status);

-- PO numbers are drawn from a sequence and rendered base-36, so the sequence moves with the
-- aggregate. Starting it above the pos-inventory high-water mark is the migration's job (V11).
CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;
