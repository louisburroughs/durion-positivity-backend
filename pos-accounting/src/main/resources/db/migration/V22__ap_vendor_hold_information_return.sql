-- CAP:550 #2615: the vendor's AP payment hold (with a reason) and its information-return reportable flag, on S24's
-- ap_vendor_settings (SPEC-accounting-workspace §4.9 "What stays accounting's"). The hold is a cash control: it
-- stops POST /v1/accounting/ap/payments for the vendor, never a bill's approval or posting. The information-return
-- columns store which configured form and box the vendor's payments are reported in; the forms, boxes and payee-id
-- schemes are pos-tax configuration per country (AW48, placeholders under OI-4), so no CHECK names a value. No
-- taxpayer-number column exists here: the number is pos-supplier's, and accounting serves at most its copied last4.
-- RLS is already on the table (V20, ADR-0062). No backfill: every existing row is "not held, not reportable".

ALTER TABLE public.ap_vendor_settings
    ADD COLUMN ap_hold boolean NOT NULL DEFAULT false,
    ADD COLUMN ap_hold_reason character varying(500),
    ADD COLUMN ap_hold_set_by character varying(255),
    ADD COLUMN ap_hold_set_at timestamp(6) with time zone,
    ADD COLUMN information_return_reportable boolean NOT NULL DEFAULT false,
    ADD COLUMN information_return_form character varying(32),
    ADD COLUMN information_return_box character varying(10),
    ADD COLUMN information_return_payee_scheme character varying(16),
    ADD CONSTRAINT ap_vendor_settings_hold_check CHECK (
        (ap_hold AND ap_hold_reason IS NOT NULL AND char_length(btrim(ap_hold_reason)) >= 10
            AND ap_hold_set_by IS NOT NULL AND ap_hold_set_at IS NOT NULL)
        OR (NOT ap_hold AND ap_hold_reason IS NULL AND ap_hold_set_by IS NULL AND ap_hold_set_at IS NULL)),
    ADD CONSTRAINT ap_vendor_settings_information_return_check CHECK (
        (information_return_reportable AND information_return_form IS NOT NULL AND information_return_box IS NOT NULL)
        OR (NOT information_return_reportable AND information_return_form IS NULL
            AND information_return_box IS NULL AND information_return_payee_scheme IS NULL));

COMMENT ON COLUMN public.ap_vendor_settings.ap_hold IS
    'AP payment hold (#2615): true refuses POST /v1/accounting/ap/payments for the vendor (422 VENDOR_ON_AP_HOLD); '
    'approval and posting go ahead. Independent of the vendor''s status.';
COMMENT ON COLUMN public.ap_vendor_settings.ap_hold_reason IS
    'Why the vendor is held, 10-500 characters. Staff free text handled as CONFIDENTIAL (ADR-0072): served in reads '
    'and audit rows, never in a log line, an error message or a metric tag.';
COMMENT ON COLUMN public.ap_vendor_settings.information_return_form IS
    'A form code from pos-tax''s information-return configuration for the tenant''s tax country (#2615); validated '
    'at write time, so no CHECK names a value.';
COMMENT ON COLUMN public.ap_vendor_settings.information_return_payee_scheme IS
    'The tax-registration scheme the payee is reported under (one of the form''s payee-id schemes). The number itself '
    'is never stored in pos-accounting.';
