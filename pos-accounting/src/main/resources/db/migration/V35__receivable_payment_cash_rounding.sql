-- ADR-0067 A8 (#2662): a cash tender's signed rounding (settledAmount - amount) rides on the receivable payment until
-- the first cash-receipt entry (an application or a customer-credit issuance) books it: Dr Undeposited Funds for the
-- cash taken, Cr/Dr 6050 CASH_ROUNDING_DIFFERENCE for the difference. NULL on card tenders and on payments recorded
-- before A8; cash_rounding_posted marks that an entry has carried it so it is never booked twice.
ALTER TABLE receivable_payment
    ADD COLUMN cash_rounding_amount numeric(19, 4),
    ADD COLUMN cash_rounding_posted boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN receivable_payment.cash_rounding_amount IS
    'ADR-0067 A8: settledAmount - amount of the cash tender, signed; NULL when none';
COMMENT ON COLUMN receivable_payment.cash_rounding_posted IS
    'ADR-0067 A8: true once a cash-receipt entry has booked the rounding to 6050';
