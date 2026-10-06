-- CAP:550 S8 (#2506): the customer-party replica carries the owner's house-account kind, so
-- checkout can recognise the tenant's CASH (walk-in) account from the flag alone and never from a
-- name or a customer number. NULL for every ordinary party and for rows replicated before
-- pos-customer published the field; a party-fact replay fills existing rows.
ALTER TABLE public.ext_customer
    ADD COLUMN house_account character varying(20);
