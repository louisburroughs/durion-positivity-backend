-- Issue #927: quarantine/hold tracking for warranty defective-part returns.
-- Fed by warranty.events.v1 (warranty.part-return.requested / .shipped); pos-warranty owns
-- the part-return lifecycle — this table is inventory's tracking record for the quarantine
-- hold shelf (the physical hold stays a manual process in v1). Never written by inventory
-- business logic.
CREATE TABLE warranty_part_return_hold (
    part_return_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    claim_line_id uuid,
    product_entity_id uuid,
    serial_number varchar(128),
    disposition varchar(64),
    status varchar(32) NOT NULL,
    carrier varchar(64),
    tracking_number varchar(128),
    requested_at timestamp(6) with time zone,
    shipped_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT warranty_part_return_hold_pkey PRIMARY KEY (part_return_id)
);

CREATE INDEX idx_warranty_part_return_hold_status ON warranty_part_return_hold (status);
