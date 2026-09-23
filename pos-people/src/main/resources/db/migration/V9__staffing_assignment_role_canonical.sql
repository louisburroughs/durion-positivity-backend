-- #2173: a staffing assignment's role is a code its consumers match on, not display text.
-- pos-shop-manager keys its mechanic projection, location roster and booking presence check on
-- TECHNICIAN, some of those case-sensitively, so an assignment stored as "technician" counted as
-- staffing for bookings yet never produced a mechanic. StaffingAssignmentServiceImpl now stores
-- and publishes the trimmed, upper-cased role; this migration brings existing rows to that form
-- and adds a CHECK so a write path that skips the service's canonicalization fails loudly instead
-- of storing a variant silently.
--
-- Canonicalizing, rather than a case-insensitive index as V8 chose for job_role codes: V8 was
-- about uniqueness of a code the tenant names, where preserving the tenant's casing was the
-- point. Here the problem is every consumer comparing against one fixed code, and a case-folded
-- index would not change what the replicas receive. The CHECK gives the same "enforced once, where
-- nothing can route around it" guarantee V8 argued for.
--
-- Pre-existing collisions: employee_location_assignment_employee_id_location_id_role_e_key is
-- UNIQUE (tenant_id, employee_id, location_id, role, effective_from), so two rows differing only
-- by the role's case or surrounding whitespace would collide once canonicalized, and the UPDATE
-- would fail deploy. The DO block keeps the oldest row of each such group on the canonical role
-- and gives every later row the canonical role plus the lowest free '_DUPn' suffix, with a
-- WARNING naming it: the same rename-the-loser shape as V8. A renamed row keeps its id, dates
-- and status; an operator ends it, or corrects its role, through the API after deploy. Where a
-- database never stored a non-canonical role, which is every seed and fixture path today, the
-- block finds nothing and says so.
--
-- Seeing every tenant: employee_location_assignment is under FORCE ROW LEVEL SECURITY and Flyway
-- connects as the owner, which carries a transitional default tenant binding
-- (postgres/init-tenancy.sh). Without lifting FORCE, the block would only see that one tenant
-- and the CHECK (which validates the whole heap) would fail on any other tenant's rows. FORCE is
-- dropped for the migration's single transaction and restored before commit, as in V8.
ALTER TABLE public.employee_location_assignment NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    rec       RECORD;
    n         INTEGER;
    suffix    TEXT;
    candidate TEXT;
    renamed   INTEGER := 0;
    fixed     INTEGER := 0;
BEGIN
    FOR rec IN
        SELECT id, tenant_id, employee_id, location_id, effective_from, role,
               upper(btrim(role)) AS canonical,
               row_number() OVER (PARTITION BY tenant_id, employee_id, location_id,
                                               upper(btrim(role)), effective_from
                                      ORDER BY (role = upper(btrim(role))) DESC, created_at, id) AS rn
          FROM public.employee_location_assignment
         ORDER BY id
    LOOP
        CONTINUE WHEN rec.rn = 1 AND rec.role = rec.canonical;

        IF rec.rn = 1 THEN
            candidate := rec.canonical;
        ELSE
            n := 1;
            LOOP
                n := n + 1;
                suffix := '_DUP' || n;
                candidate := left(rec.canonical, 255 - length(suffix)) || suffix;
                EXIT WHEN NOT EXISTS (SELECT 1
                                        FROM public.employee_location_assignment
                                       WHERE tenant_id = rec.tenant_id
                                         AND employee_id = rec.employee_id
                                         AND location_id = rec.location_id
                                         AND effective_from = rec.effective_from
                                         AND upper(btrim(role)) = candidate);
                IF n > 50 THEN
                    RAISE EXCEPTION 'V9: could not find a free role for assignment % after 50 attempts', rec.id;
                END IF;
            END LOOP;
            renamed := renamed + 1;
            RAISE WARNING 'V9: staffing assignment % (tenant %) duplicated another assignment once its role '
                          '"%" was canonicalized, and was given role "%". End it, or correct its role, '
                          'through the API.',
                          rec.id, rec.tenant_id, rec.role, candidate;
        END IF;

        UPDATE public.employee_location_assignment SET role = candidate WHERE id = rec.id;
        fixed := fixed + 1;
    END LOOP;

    IF fixed = 0 THEN
        RAISE NOTICE 'V9: every staffing assignment role was already canonical.';
    ELSE
        RAISE NOTICE 'V9: canonicalized % staffing assignment role(s), % renamed as duplicates.', fixed, renamed;
    END IF;
END $$;

ALTER TABLE public.employee_location_assignment FORCE ROW LEVEL SECURITY;

ALTER TABLE public.employee_location_assignment
    ADD CONSTRAINT employee_location_assignment_role_canonical CHECK (role = upper(btrim(role)));

COMMENT ON CONSTRAINT employee_location_assignment_role_canonical ON public.employee_location_assignment IS
    '#2173: staffing roles are stored trimmed and upper-cased, the form StaffingAssignmentServiceImpl writes and every consumer matches on.';
