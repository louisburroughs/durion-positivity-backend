-- #2558 (CAP:550; Accounting Domain ruling 2026-10-06): the tenant's accounting-calendar zone, the zone in which
-- pos-accounting dates every posting and cuts every period. It is the ACCOUNTING_TIME_ZONE row of
-- accounting_configuration (an IANA region id); there is no runtime default, so a tenant without the row posts
-- nothing (SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET).
--
-- Every tenant that already has accounting data gets UTC: the zone the module's UTC clock dated everything in until
-- now, so nothing already posted is re-cut. An administrator sets the legal entity's real zone with
-- PUT /v1/accounting/configuration/time-zone before the first period close. New tenants get UTC from tenant
-- provisioning (DataInitializationServiceImpl, #2526), which also covers a registry tenant this statement does not see.
--
-- Tenancy: the tables read and written here are under FORCE ROW LEVEL SECURITY, and the owner running Flyway would
-- otherwise see only its bound tenant (none here). As V8 does, the migration lifts FORCE for its own statement and
-- restores it; it is one transaction, so nobody observes a table without it. Each row names its tenant explicitly.
-- The id is a UUIDv7 with a literal timestamp and bits derived from the tenant id, and the timestamps are literal
-- (ADR-0024: no SQL clock), as in V5.
ALTER TABLE public.accounting_configuration NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_state NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_period NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.journal_entry NO FORCE ROW LEVEL SECURITY;

INSERT INTO public.accounting_configuration
    (tenant_id, config_id, config_key, config_value, created_at, created_by, modified_at, modified_by)
SELECT t.tenant_id,
       ('01999999-2558-7' || substr(md5(t.tenant_id::text), 1, 3) || '-8' || substr(md5(t.tenant_id::text), 4, 3)
            || '-' || substr(md5(t.tenant_id::text), 7, 12))::uuid,
       'ACCOUNTING_TIME_ZONE',
       'UTC',
       '2026-10-06 00:00:00+00',
       'accounting-time-zone',
       '2026-10-06 00:00:00+00',
       'accounting-time-zone'
FROM (SELECT tenant_id FROM public.accounting_template_state
      UNION
      SELECT tenant_id FROM public.accounting_configuration
      UNION
      SELECT tenant_id FROM public.accounting_period
      UNION
      SELECT tenant_id FROM public.gl_account
      UNION
      SELECT tenant_id FROM public.journal_entry) t
-- The platform tenant holds the accounting template and never posts.
WHERE t.tenant_id <> '01900000-0000-7000-8000-000000000000'::uuid
ON CONFLICT (tenant_id, config_key) DO NOTHING;

ALTER TABLE public.accounting_configuration FORCE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_state FORCE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_period FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_account FORCE ROW LEVEL SECURITY;
ALTER TABLE public.journal_entry FORCE ROW LEVEL SECURITY;
