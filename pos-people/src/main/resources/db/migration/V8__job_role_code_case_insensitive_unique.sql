-- Code review fix (durion#2157): JobRoleServiceImpl.create() rejects a duplicate job-role code
-- through a case-INSENSITIVE pre-check (existsByCodeIgnoreCase), but V6__job_role.sql's
-- job_role_tenant_code_key is UNIQUE (tenant_id, code) -- ordinary btree equality over the raw
-- string, which is case-SENSITIVE under Postgres's default collation. Two concurrent creates for
-- "LEAD_TECH" and "lead_tech" both pass the case-insensitive pre-check (neither has committed when
-- the other reads) and both then satisfy the case-sensitive constraint, because the two strings are
-- literally different. Both commit. The API told the caller job-role codes are unique
-- case-insensitively; the database, which is supposed to be the backstop for exactly the race the
-- pre-check cannot close, was enforcing a weaker rule and let the collision through.
--
-- Two ways to make the two agree: (a) make the constraint case-insensitive -- UNIQUE (tenant_id,
-- lower(code)) -- or (b) canonicalize the stored code (upper-case it) so the existing case-sensitive
-- constraint becomes sufficient. This migration takes (a) and changes no application code. (b) only
-- holds for as long as every write path remembers to canonicalize before insert -- a bulk loader, an
-- admin console, a future PATCH endpoint -- and a single call site forgetting that discipline is the
-- bug being fixed here. It would also silently rewrite whatever casing a tenant typed, which nothing
-- documents as disallowed. A case-insensitive index enforces the invariant once, in the one place
-- nothing can route around it, regardless of what any caller does or forgets to do. (A genuinely
-- simultaneous insert of the identical code still races past existsByCodeIgnoreCase the same way it
-- always could; that case is unchanged by this migration and is already caught at the database and
-- translated to 409 DUPLICATE_RESOURCE by pos-web-common's GlobalApiExceptionHandler /
-- DataIntegrityViolations, which classifies any SQLSTATE 23505 unique violation, unique index
-- included.)
--
-- Pre-existing collisions: existsByCodeIgnoreCase has been the only server-side gate since V6
-- shipped, so a database that hit this bug can already hold two job_role rows in the same tenant
-- differing only by case -- precisely what the new index forbids. CREATE UNIQUE INDEX fails
-- outright against existing violations, and this migration must not fail deploy (the ADR-0062
-- baseline is already live on alpha). The DO block below finds every such group -- partitioned by
-- tenant_id and the case-folded code, exactly what the new index enforces -- leaves the oldest row
-- of each group untouched, and appends the lowest free '_DUPn' suffix to every later row's code.
-- This is the same rename-the-loser-and-warn shape V3__tenant_display_name_key.sql (pos-tenant)
-- uses for its own pre-existing collisions. A renamed row is not deactivated or deleted: its id,
-- its name, its description and every employee referencing it are untouched, so nothing that reads
-- it by id breaks; only the code changes, to a value nobody could mistake for intentional, and a
-- WARNING names the row so an operator can give it a real, distinct code (or deactivate/merge it)
-- after deploy. Where a database never hit the bug -- the expected case everywhere this has not
-- already gone wrong -- the block finds nothing and says so.
--
-- Seeing every tenant, not just one: V6 put job_role under FORCE ROW LEVEL SECURITY, which strips
-- even the table owner's normal RLS bypass, and Flyway connects on that owner credential
-- (postgres/init-tenancy.sh: "the owner credential stays Flyway-only from then on"), which today
-- also carries a transitional role-level default, ALTER ROLE ... SET app.current_tenant = <alpha
-- default tenant> (same script). Left alone, the DO block's own SELECT would be filtered by
-- tenant_isolation down to that one bound tenant, and every other tenant's collisions would go
-- unrenamed and then fail CREATE UNIQUE INDEX (which scans the whole heap, unfiltered by RLS) --
-- the exact deploy failure this block exists to prevent. Unlike V2__seed_tenant.sql and
-- V3__tenant_display_name_key.sql in pos-tenant, which may bind a single tenant because every row
-- there belongs to the platform tenant, job_role has one row group per real tenant, so this
-- migration instead drops FORCE for its duration -- restored before commit -- so the owner sees
-- every tenant regardless of what is or is not bound. Flyway runs a migration in one transaction
-- and the ALTER takes a table lock, so no other session observes the window.
ALTER TABLE public.job_role NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    dup       RECORD;
    n         INTEGER;
    room      INTEGER;
    suffix    TEXT;
    candidate TEXT;
    renamed   INTEGER := 0;
BEGIN
    FOR dup IN
        SELECT id, tenant_id, code
          FROM (SELECT id,
                       tenant_id,
                       code,
                       row_number() OVER (PARTITION BY tenant_id, lower(code)
                                              ORDER BY created_at, id) AS rn
                  FROM public.job_role) ranked
         WHERE rn > 1
         ORDER BY id
    LOOP
        n := 1;
        LOOP
            n := n + 1;
            suffix := '_DUP' || n;
            room := 64 - length(suffix);
            candidate := left(dup.code, room) || suffix;
            EXIT WHEN NOT EXISTS (SELECT 1
                                    FROM public.job_role
                                   WHERE tenant_id = dup.tenant_id
                                     AND lower(code) = lower(candidate));
            IF n > 50 THEN
                RAISE EXCEPTION 'V8: could not find a free code for job_role % after 50 attempts', dup.id;
            END IF;
        END LOOP;

        UPDATE public.job_role SET code = candidate WHERE id = dup.id;

        renamed := renamed + 1;
        RAISE WARNING 'V8: job_role % (tenant %) collided case-insensitively with another role and '
                      'was renamed to code "%". Give it its own distinct code through the API, or '
                      'deactivate/merge it if it should not have existed.',
                      dup.id, dup.tenant_id, candidate;
    END LOOP;

    IF renamed = 0 THEN
        RAISE NOTICE 'V8: no case-insensitive job_role code collisions found; every row kept its code.';
    END IF;
END $$;

ALTER TABLE public.job_role FORCE ROW LEVEL SECURITY;

-- job_role_tenant_code_key (tenant_id, code) is now strictly weaker than, and fully implied by,
-- the case-insensitive index below (two equal strings are also equal case-folded), so it is
-- dropped instead of left in place doing overlapping work alongside it.
ALTER TABLE public.job_role DROP CONSTRAINT job_role_tenant_code_key;

CREATE UNIQUE INDEX job_role_tenant_code_ci_key ON public.job_role (tenant_id, lower(code));

COMMENT ON INDEX public.job_role_tenant_code_ci_key IS
    'durion#2157 code review fix: case-insensitive job-role code uniqueness per tenant, matching JobRoleServiceImpl.create()''s existsByCodeIgnoreCase pre-check. Supersedes job_role_tenant_code_key (V6), which was case-sensitive and let differently-cased duplicates through.';
