-- CAP-320 #1334: the order's outstanding value, carried on the fact so receiving can refuse a
-- receipt worth more than is still owed at the moment the receiver is standing there, rather than
-- accepting it and having pos-order reject the resulting fact later.
ALTER TABLE ext_purchase_order ADD COLUMN open_balance_minor bigint;
