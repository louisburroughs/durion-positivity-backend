-- Warranty claims integration (PRD 9.4):
--   * optional external_reference correlation id on invoice adjustments and refund records
--     (carries e.g. the warranty claim code so warranty settlements can be traced to the
--     invoice-side financial records);
--   * new WARRANTY invoice adjustment type (warranty credit).
-- H2-compatible: plain ADD COLUMN and named-constraint DROP/ADD only.

ALTER TABLE invoice_adjustments ADD COLUMN external_reference character varying(64);

ALTER TABLE refund_records ADD COLUMN external_reference character varying(64);

-- Widen the adjustment type check to accept the new WARRANTY value.
ALTER TABLE invoice_adjustments DROP CONSTRAINT invoice_adjustments_type_check;
ALTER TABLE invoice_adjustments ADD CONSTRAINT invoice_adjustments_type_check
    CHECK (type IN ('DISCOUNT', 'FEE', 'CORRECTION', 'WARRANTY'));
