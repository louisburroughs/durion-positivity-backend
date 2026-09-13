-- Tenant display names become the name users sign in by, so they must be unique and must match
-- case- and whitespace-insensitively. display_name_key carries the normalized form the uniqueness
-- constraint and the login search both use.
--
-- The key is written by the application (TenantDisplayNameAllocator), not by a generated column:
-- the dev profile runs H2 with Flyway disabled and the schema built from the entities
-- (application-dev.yml), so a database-generated column would be absent and null there and under
-- every H2-backed test, leaving the collision check querying a column nothing populates. NFKC also
-- has no plain-SQL equivalent outside normalize(), so the application must normalize regardless and
-- a generated column would define a second, subtly different normalization beside it. The unique
-- constraint below is what enforces uniqueness; only the normalization is application-side.
--
-- Row-level security is FORCEd on public.tenant, so the backfill and de-duplication below bind the
-- platform tenant first, exactly as V2__seed_tenant.sql does. Every registry row belongs to it.

ALTER TABLE public.tenant
    ADD COLUMN display_name_key character varying(200);

SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000000', true);

-- Backfill. normalize(..., NFKC) matches step 1 of the application's normalization; the remaining
-- steps are internal-whitespace collapse, trim and case-fold, in that order.
--
-- display_name is tidied to the same display form the application stores (TenantDisplayNameAllocator
-- .displayForm: NFKC, whitespace collapsed, trimmed, the operator's casing kept) so a migrated row
-- is indistinguishable from one written afterwards; only the case-fold separates it from the key.
-- left(..., 200) on both, and on the key *after* lowercasing, because either step can lengthen a
-- value that already fits: NFKC expands compatibility forms (one U+FB03 ligature becomes three
-- characters) and lower() expands others (U+0130 becomes two). Without the bound a single such
-- legacy row fails the whole migration with a value-too-long error. This mirrors
-- TenantDisplayName.displayForm/normalize, which bound the same two steps the same way.
UPDATE public.tenant
   SET display_name = left(btrim(regexp_replace(normalize(display_name, NFKC), '\s+', ' ', 'g')), 200);

UPDATE public.tenant
   SET display_name_key = left(lower(display_name), 200);

-- De-duplicate anything the new constraint would reject. The oldest row of each colliding group
-- keeps its name; every later one gets the lowest free ' #<n>' suffix, which is the same shape the
-- allocator produces for a second tenant under one account. Both columns move together so the
-- displayed name and its key never disagree.
DO $$
DECLARE
    dup             RECORD;
    n               INTEGER;
    room            INTEGER;
    suffix          TEXT;
    candidate_name  TEXT;
    candidate_key   TEXT;
BEGIN
    FOR dup IN
        SELECT id, tenant_id, display_name, display_name_key
          FROM (SELECT id,
                       tenant_id,
                       display_name,
                       display_name_key,
                       row_number() OVER (PARTITION BY tenant_id, display_name_key
                                              ORDER BY created_at, id) AS rn
                  FROM public.tenant) ranked
         WHERE rn > 1
         ORDER BY id
    LOOP
        n := 1;
        LOOP
            n := n + 1;
            suffix := ' #' || n;
            -- Keep the result inside display_name's 200 characters.
            room := 200 - length(suffix);
            candidate_name := left(dup.display_name, room) || suffix;
            candidate_key := left(dup.display_name_key, room) || suffix;
            EXIT WHEN NOT EXISTS (SELECT 1
                                    FROM public.tenant
                                   WHERE tenant_id = dup.tenant_id
                                     AND display_name_key = candidate_key);
            IF n > 50 THEN
                RAISE EXCEPTION 'V3: could not find a free display name for tenant % after 50 attempts', dup.id;
            END IF;
        END LOOP;

        UPDATE public.tenant
           SET display_name = candidate_name,
               display_name_key = candidate_key
         WHERE id = dup.id;

        RAISE NOTICE 'V3: renamed tenant % to "%" so display names can be made unique', dup.id, candidate_name;
    END LOOP;
END $$;

ALTER TABLE public.tenant
    ALTER COLUMN display_name_key SET NOT NULL;

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_display_name_key UNIQUE (tenant_id, display_name_key);
