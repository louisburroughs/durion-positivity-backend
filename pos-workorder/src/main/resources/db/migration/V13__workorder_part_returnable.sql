-- Order parity story E1 (#1077, resolved order-spec Q6): explicit settled-line returnability.
-- Null means "not marked"; consumers (pos-order counter returns) never infer it.
ALTER TABLE workorder_part ADD COLUMN returnable BOOLEAN;
