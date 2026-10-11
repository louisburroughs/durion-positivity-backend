-- #2633: a purchase order's open balance is ex-tax. It used to start at the grand total (subtotal + tax)
-- while every goods receipt took off an ex-tax accrual, so a taxed order received in full kept its tax as
-- an outstanding balance forever. New orders and revisions now start at the subtotal; this moves the rows
-- written before that onto the same basis by taking the order's tax off, floored at zero (a fully
-- received order goes to zero). CANCELLED orders are left as they are. Pre-production: no compatibility
-- shim, and no fact is republished here, so pos-inventory's copy of a balance changes with the order's
-- next purchaseorder.updated.
--
-- Tenancy: purchase_order is under FORCE ROW LEVEL SECURITY. Flyway runs as the owner; a superuser owner
-- sees every tenant, otherwise the owner's bound tenant only. The update reads and writes only the row's
-- own columns, so it never mixes tenants. updated_at and version_number are left alone: this restates a
-- figure on its documented basis, it is not an edit of the order (and ADR-0024 forbids SQL-clock writes).
UPDATE public.purchase_order
SET open_balance_minor = GREATEST(open_balance_minor - tax_minor, 0)
WHERE status <> 'CANCELLED'
  AND open_balance_minor IS NOT NULL
  AND tax_minor > 0;
