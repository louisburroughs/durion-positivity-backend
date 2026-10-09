-- CAP:550 S43 (#2604, AW44): purchase-tax rules by country. A vendor bill charging tax on goods for resale is held
-- for a person when the tax country's pos-tax rule says HOLD; it goes through when the vendor accepts such tax
-- (ap_vendor_settings.accept_tax_on_resale_goods) or the approver overrides it for the one bill, with a
-- justification. vendor_bill records which override let the bill through. The rules themselves are pos-tax
-- configuration per country (AW48, placeholders under OI-4), so no CHECK names a country.
-- RLS is already on both tables (ADR-0062). No backfill: no existing vendor accepts the tax, no bill was overridden.

ALTER TABLE public.ap_vendor_settings
    ADD COLUMN accept_tax_on_resale_goods boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public.ap_vendor_settings.accept_tax_on_resale_goods IS
    'CAP:550 S43: true lets a bill of this vendor that charges tax on goods for resale be approved without a per-bill '
    'override, where the tax country''s purchase-tax rule holds such bills. Honoured at the next decision.';

ALTER TABLE public.vendor_bill
    ADD COLUMN tax_on_resale_override character varying(20),
    ADD COLUMN tax_on_resale_override_justification character varying(1000),
    ADD CONSTRAINT vendor_bill_tax_on_resale_override_check CHECK (
        (tax_on_resale_override IS NULL AND tax_on_resale_override_justification IS NULL)
        OR (tax_on_resale_override = 'VENDOR_SETTING' AND tax_on_resale_override_justification IS NULL)
        OR (tax_on_resale_override = 'BILL'
            AND char_length(btrim(tax_on_resale_override_justification)) >= 10));

COMMENT ON COLUMN public.vendor_bill.tax_on_resale_override IS
    'CAP:550 S43: what let an approved bill charging tax on goods for resale through a HOLD rule: BILL (the approver, '
    'with a justification) or VENDOR_SETTING (the vendor accepts the tax). Null when the hold did not apply.';
COMMENT ON COLUMN public.vendor_bill.tax_on_resale_override_justification IS
    'The approver''s justification of a BILL override, 10-1000 characters. Staff free text handled as CONFIDENTIAL '
    '(ADR-0072): served in the bill read, never in a log line, an error message or a metric tag.';
