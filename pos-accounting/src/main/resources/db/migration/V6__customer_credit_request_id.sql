-- #2524 (CAP:550 S35; SPEC-accounting-workspace §5.3 item 5, §8.2, §9.3; AD-003, AD-010): a customer
-- credit records the id of the command that issued it, so a replay of that command returns the same
-- credit instead of issuing a second one.
--
-- The value is namespaced by the issuing path (APPLY:<applicationRequestId> for applyPayment,
-- REMAINDER:<requestId> for creditPaymentRemainder, and the invoice-payment event's own prefix for
-- credits issued from settled payments), so an apply key equal to a remainder key never collides.
-- The same namespaced value keys the credit-issuance GL posting. Credits issued before this
-- migration carry no request id; nothing is backfilled because their issuing command cannot be
-- known.
--
-- Tenancy: the unique index leads with tenant_id (TENANCY_SCHEMA.md "Adding a table" step 2) and is
-- partial so pre-existing rows and credits without a command id do not collide on NULL.

ALTER TABLE public.customer_credit ADD COLUMN request_id character varying(120);

COMMENT ON COLUMN public.customer_credit.request_id IS
    'Namespaced id of the command that issued the credit (#2524): APPLY:<applicationRequestId>, '
    'REMAINDER:<requestId> or the invoice-payment event prefix. Unique per tenant; NULL for credits '
    'issued before the column existed.';

CREATE UNIQUE INDEX uq_customer_credit_request_id
    ON public.customer_credit USING btree (tenant_id, request_id)
    WHERE (request_id IS NOT NULL);
