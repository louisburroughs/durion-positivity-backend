-- CAP-326 (durion#483): DECISION-SHOPMGMT-002's persisted conflict model, so a conflict carries a
-- severity and a rule reference and the record's own audit query ("HARD conflicts with overrides;
-- should be zero") can finally run. Shape follows the record's DDL at DOMAIN_NOTES.md:161-185 with
-- three deliberate departures, each recorded in the spec:
--   * varchar + CHECK, not CREATE TYPE -- this module's baseline has none and this does not start one;
--   * no scheduling_conflict.override_id -- conflict_override.conflict_id is the one direction, so
--     the two tables are not circular and an override row is the proof of an override;
--   * conflict_rule is platform reference data (spec D18.2): no tenant_id, no RLS, listed in
--     tenancy-global-tables.txt. The code is the API reason code -- one namespace for every tenant --
--     and a tenant must not be able to deactivate BAY_DOUBLE_BOOKED while the exclusion constraint
--     keeps enforcing it. Its rows are seeded by R__seed_shop_manager_1_conflict_rules.sql.
--
-- override_record goes (spec D18.3): it recorded a reason, free text and an actor but no severity
-- and no rule, so DECISION-007's audit could not run against it, and two override tables would be
-- exactly the parallel path #483 forbids. With it go the two denormalised flags: nothing read
-- appointment.is_conflict_override, and reschedule_history.conflict_overridden was written
-- unconditionally false. Pre-production policy: replaced, not shimmed.

-- ── conflict_rule: the platform catalog ─────────────────────────────────────────────────────────
CREATE TABLE public.conflict_rule (
    id uuid NOT NULL,
    code character varying(50) NOT NULL,
    severity character varying(8) NOT NULL,
    resource_type character varying(16) NOT NULL,
    message_template text NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT conflict_rule_pkey PRIMARY KEY (id),
    CONSTRAINT conflict_rule_code_key UNIQUE (code),
    CONSTRAINT conflict_rule_severity_check CHECK (severity IN ('HARD', 'SOFT')),
    CONSTRAINT conflict_rule_resource_type_check
        CHECK (resource_type IN ('BAY', 'MECHANIC', 'CAPACITY', 'HOURS', 'SKILL'))
);
COMMENT ON TABLE public.conflict_rule IS
    'DECISION-SHOPMGMT-002 rule catalog (CAP-326). Platform-global: code is the API reason code, one namespace for every tenant. Seeded; is_active is a platform switch.';

-- ── scheduling_conflict: one row per rule that fired against a booking attempt ─────────────────
-- appointment_id is NULL for a refused attempt (HARD): the conflict is the record that nothing was
-- booked. location_id is carried so the DECISION-002 override monitor can scope refused attempts too.
CREATE TABLE public.scheduling_conflict (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    appointment_id uuid,
    conflict_rule_id uuid NOT NULL,
    severity character varying(8) NOT NULL,
    location_id uuid NOT NULL,
    resource_id character varying(128),
    attempted_start_at timestamp(6) with time zone NOT NULL,
    attempted_end_at timestamp(6) with time zone NOT NULL,
    detail text,
    detected_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT scheduling_conflict_pkey PRIMARY KEY (id),
    CONSTRAINT scheduling_conflict_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT scheduling_conflict_severity_check CHECK (severity IN ('HARD', 'SOFT')),
    CONSTRAINT scheduling_conflict_window_check CHECK (attempted_start_at < attempted_end_at),
    CONSTRAINT fk_scheduling_conflict_rule FOREIGN KEY (conflict_rule_id) REFERENCES public.conflict_rule(id),
    CONSTRAINT fk_scheduling_conflict_appointment FOREIGN KEY (tenant_id, appointment_id)
        REFERENCES public.appointment(tenant_id, appointment_id)
);
CREATE INDEX scheduling_conflict_tenant_idx ON public.scheduling_conflict USING btree (tenant_id);
CREATE INDEX scheduling_conflict_appointment_idx
    ON public.scheduling_conflict USING btree (tenant_id, appointment_id);
CREATE INDEX scheduling_conflict_detected_idx
    ON public.scheduling_conflict USING btree (tenant_id, location_id, detected_at);
ALTER TABLE public.scheduling_conflict ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.scheduling_conflict FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.scheduling_conflict
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
COMMENT ON COLUMN public.scheduling_conflict.severity IS
    'Copied from the rule at detection so the audit query needs no join to say HARD; a rule''s severity does not change under a recorded conflict.';
COMMENT ON COLUMN public.scheduling_conflict.appointment_id IS
    'NULL for a refused (HARD) attempt: no appointment was created. Set for a SOFT conflict the booking proceeded under.';

-- ── conflict_override: a manager''s recorded acceptance of one SOFT conflict ──────────────────
-- Immutable (DECISION-007): no updated_at, and the repository exposes no update or delete. One
-- override per conflict; a second attempt is refused, not layered. Single-actor approval:
-- approved_by = overridden_by and approved_at = created_at, so DECISION-002's second audit query
-- (SOFT overrides without approval; should be zero) holds without a two-step flow.
CREATE TABLE public.conflict_override (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    conflict_id uuid NOT NULL,
    overridden_by character varying(255) NOT NULL,
    override_reason character varying(2000) NOT NULL,
    approved_by character varying(255) NOT NULL,
    approved_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT conflict_override_pkey PRIMARY KEY (id),
    CONSTRAINT conflict_override_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT conflict_override_conflict_key UNIQUE (tenant_id, conflict_id),
    CONSTRAINT fk_conflict_override_conflict FOREIGN KEY (tenant_id, conflict_id)
        REFERENCES public.scheduling_conflict(tenant_id, id)
);
CREATE INDEX conflict_override_tenant_idx ON public.conflict_override USING btree (tenant_id);
CREATE INDEX conflict_override_actor_idx
    ON public.conflict_override USING btree (tenant_id, overridden_by, created_at);
ALTER TABLE public.conflict_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conflict_override FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.conflict_override
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- ── retire the shape that could not be audited ─────────────────────────────────────────────────
DROP TABLE public.override_record;
ALTER TABLE public.appointment DROP COLUMN is_conflict_override;
ALTER TABLE public.reschedule_history DROP COLUMN conflict_overridden;
