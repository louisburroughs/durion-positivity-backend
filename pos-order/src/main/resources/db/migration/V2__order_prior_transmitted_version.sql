-- #2492: remembers transmitted_version_number from before the in-flight transmission request, so a
-- request pos-supplier refuses to dispatch (supplier.order.notdispatched) can be rolled back.
-- Nullable: no value before the first request, and requests already in flight when this ships.
ALTER TABLE public.purchase_order
    ADD COLUMN prior_transmitted_version_number integer;
