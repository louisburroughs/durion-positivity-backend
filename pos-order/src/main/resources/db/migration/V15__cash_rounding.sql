-- ADR-0067 A8 (#2662): a CASH tender records the cash that changed hands beside the amount applied.
-- amount stays the amount applied to the order (what pos-invoice settled and pos-accounting applied
-- to AR); settled_amount is the rounded cash the drawer holds, cash_rounding_amount the signed
-- difference (settled_amount - amount) and cash_increment the coin the register rounded to.
-- All three stay NULL on card, on-account and other tenders and on rows written before A8, which
-- read as settled_amount = amount and no rounding.
ALTER TABLE order_payment_record
    ADD COLUMN settled_amount       numeric(19, 4),
    ADD COLUMN cash_rounding_amount numeric(19, 4),
    ADD COLUMN cash_increment       numeric(19, 4);

COMMENT ON COLUMN order_payment_record.settled_amount IS
    'ADR-0067 A8: cash that changed hands after rounding to the cash increment; NULL = amount';
COMMENT ON COLUMN order_payment_record.cash_rounding_amount IS
    'ADR-0067 A8: settled_amount - amount, signed; NULL = 0';
COMMENT ON COLUMN order_payment_record.cash_increment IS
    'ADR-0067 A8: the cash increment the register rounded to; NULL when the currency has none';
