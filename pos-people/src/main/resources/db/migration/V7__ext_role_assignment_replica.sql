-- durion#2155 (wave 2): a local replica of role assignments, fed by
-- security.role-assignment.changed (RoleAssignmentChangedV1, ADR-0044 SS6, durion#2160) on
-- security.events.v1. pos-security-service owns role assignments; this table is pos-people's
-- event-fed copy, read by SecurityEventsListener and served back out for the employee register's
-- "application roles" column.
--
-- Why a replica and not a call: the register renders application roles per row of a 25-row page.
-- Resolving them live would mean pos-people -> pos-people-contact -> pos-security-service, three
-- calls across two service hops for a single row -- 75 calls to paint one page. The event exists
-- precisely so the register can join locally instead, the same trade already made for
-- ext_people_contact_person / ext_people_contact_user_link.
--
-- Tenant treatment matches those two tables exactly, and for the same reason: this is a replica of
-- another service's tenant-scoped operational data (who currently holds what role), not reference
-- data any tenant could agree on, so it gets the full ADR-0062 treatment -- tenant_id, RLS enabled
-- and forced, the tenant_isolation policy -- and must NOT be added to tenancy-global-tables.txt.
--
-- Keyed by assignment_id (the event's aggregateId), matching the producer's identity so the
-- upsert's stale-version guard (aggregate_version, an LWW hint per RoleAssignmentChangedV1's
-- javadoc) has something to key off. The payload is a full snapshot covering both grant and
-- revoke -- there is no separate "removed" event type -- so a revoke is applied as an update
-- (effective_end_date / revoked_at set), never a delete; the replica row for an assignment persists
-- for its whole lifecycle.
--
-- The join key to an employee is username, not user_id: employees are keyed by person_id, and
-- ExtUserLinkReplica (person_id <-> username, no user_id column at all) is the only bridge
-- pos-people holds to a security-service identity. RoleAssignmentChangedV1 carries username
-- specifically so this replica does not have to call back to resolve it (see that record's
-- class-level javadoc). username is denormalized onto every row rather than requiring a second
-- join at read time, matching how ExtUserLinkReplica itself is flat.
--
-- role_location_scope: whether the displayed role applies everywhere (ALL) or only at particular
-- locations (LOCATION) -- Role.locationScope, ADR-0061 SS1. RoleAssignment itself carries no scope
-- (see V38__drop_role_assignment_scope.sql in pos-security-service); RoleAssignmentChangedV1
-- denormalizes it from Role at publish time and validates it non-blank, so it is NOT NULL here too.
CREATE TABLE public.ext_role_assignment_replica (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    assignment_id uuid NOT NULL,
    user_id uuid NOT NULL,
    username character varying(255) NOT NULL,
    role_id uuid NOT NULL,
    role_name character varying(255) NOT NULL,
    role_location_scope character varying(50) NOT NULL,
    effective_start_date timestamp(6) without time zone NOT NULL,
    effective_end_date timestamp(6) without time zone,
    revoked_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_role_assignment_replica_pkey PRIMARY KEY (assignment_id),
    CONSTRAINT ext_role_assignment_replica_tenant_key UNIQUE (tenant_id, assignment_id)
);

-- Primary lookup the register performs: every replicated assignment for one page of employees'
-- usernames.
CREATE INDEX idx_ext_role_assignment_username
    ON public.ext_role_assignment_replica USING btree (username);

-- The effective-window filter (active-only by default, DECISION-PEOPLE-026): given a batch of
-- usernames, find the ones not revoked and currently within [effective_start_date,
-- effective_end_date). Leading on username keeps this usable for the same batched-page lookup as
-- the index above while covering the window predicate too.
CREATE INDEX idx_ext_role_assignment_username_window
    ON public.ext_role_assignment_replica USING btree (username, revoked_at, effective_start_date, effective_end_date);

CREATE INDEX ext_role_assignment_replica_tenant_idx
    ON public.ext_role_assignment_replica USING btree (tenant_id);

ALTER TABLE public.ext_role_assignment_replica ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_role_assignment_replica FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_role_assignment_replica
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

COMMENT ON TABLE public.ext_role_assignment_replica IS
    'durion#2155/#2160: read-only role-assignment replica fed by security.role-assignment.changed on security.events.v1. pos-security-service owns the facts; joined to employees by username via ext_people_contact_user_link.';
