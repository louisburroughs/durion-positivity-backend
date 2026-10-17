-- ADR-0067 A8 (CAP:550 #2662): cash tenders recorded in pos-invoice. method_type tells a register cash tender
-- (CASH, no gateway, no token) from a card capture (CARD, every existing row); payment_token therefore becomes
-- nullable. The three rounding figures travel on payment.payment.settled unchanged: settled_amount is the cash taken
-- after rounding to the currency's cash increment, cash_rounding_amount is signed (settled minus the amount applied),
-- cash_increment the increment applied (NULL when the currency has none). NULL on every card row.
ALTER TABLE payment_intents ADD COLUMN method_type varchar(20) NOT NULL DEFAULT 'CARD';
ALTER TABLE payment_intents ALTER COLUMN payment_token DROP NOT NULL;
ALTER TABLE payment_intents ADD COLUMN settled_amount numeric(15,2) NULL;
ALTER TABLE payment_intents ADD COLUMN cash_rounding_amount numeric(15,2) NULL;
ALTER TABLE payment_intents ADD COLUMN cash_increment numeric(15,2) NULL;
