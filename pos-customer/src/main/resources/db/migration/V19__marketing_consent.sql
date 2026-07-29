-- Story #1138: per-channel marketing consent, distinct from the operational channel
-- preferences. A party can want service reminders by SMS while refusing marketing on the
-- same channel, so the two must never be conflated at send time.
ALTER TABLE communication_preference
    ADD COLUMN marketing_email_consent character varying(20),
    ADD COLUMN marketing_sms_consent character varying(20),
    ADD COLUMN opt_out_reason character varying(30),
    ADD COLUMN opt_out_at timestamp(6) with time zone,
    ADD COLUMN quiet_hours_start time without time zone,
    ADD COLUMN quiet_hours_end time without time zone,
    ADD COLUMN max_marketing_sends_per_month integer;

-- Backfill from the single coarse marketing_preference flag. OPT_IN carries over to both
-- channels; anything else (including NULL, which the legacy service treated as OPT_OUT)
-- becomes an explicit OPT_OUT rather than UNSET, because those parties were in fact asked.
UPDATE communication_preference
SET marketing_email_consent = CASE WHEN marketing_preference = 'OPT_IN' THEN 'OPT_IN' ELSE 'OPT_OUT' END,
    marketing_sms_consent = CASE WHEN marketing_preference = 'OPT_IN' THEN 'OPT_IN' ELSE 'OPT_OUT' END;

ALTER TABLE communication_preference
    ALTER COLUMN marketing_email_consent SET NOT NULL,
    ALTER COLUMN marketing_sms_consent SET NOT NULL,
    ALTER COLUMN marketing_email_consent SET DEFAULT 'UNSET',
    ALTER COLUMN marketing_sms_consent SET DEFAULT 'UNSET';

ALTER TABLE communication_preference
    ADD CONSTRAINT comm_pref_marketing_email_consent_chk
        CHECK (marketing_email_consent IN ('OPT_IN', 'OPT_OUT', 'UNSET')),
    ADD CONSTRAINT comm_pref_marketing_sms_consent_chk
        CHECK (marketing_sms_consent IN ('OPT_IN', 'OPT_OUT', 'UNSET')),
    ADD CONSTRAINT comm_pref_opt_out_reason_chk
        CHECK (opt_out_reason IS NULL OR opt_out_reason IN (
            'NOT_INTERESTED', 'TOO_FREQUENT', 'NEVER_SIGNED_UP',
            'CONTENT_NOT_RELEVANT', 'LEGAL_DNC', 'OTHER'));

-- Decision O-2: account-level hard gate. An organization cannot itself consent — a person
-- does, on its behalf — but a fleet manager must be able to silence the whole account.
ALTER TABLE commercial_party
    ADD COLUMN account_marketing_opt_out boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN account_marketing_opt_out_at timestamp(6) with time zone;
