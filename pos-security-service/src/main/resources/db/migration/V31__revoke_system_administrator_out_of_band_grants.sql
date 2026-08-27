-- #1512: revoke the out-of-band grants that made SYSTEM_ADMINISTRATOR a superuser
-- on a live environment.
--
-- WHAT HAPPENED
-- Between 2026-08-24 and 2026-08-25 23:38 something granted SYSTEM_ADMINISTRATOR
-- every row then in the permissions table. On alpha the role reads 398 grants
-- against a seed that gives it 40. The investigation in #1512 accounted for the
-- 83-row gap with no remainder (48 revoked role-agnostically by V25/V26/V27/V28,
-- 35 registered after the grant ran), ruled out seed drift, the durion
-- seed-generator and a startup bootstrap, and could not name the actor -- the
-- table had no provenance columns until V30. Twelve of the grants are codes no
-- version of the seed has ever given to any role, including families ADR-0044 §6
-- retired, so this cannot be an older seed.
--
-- THE DECISION
-- SYSTEM_ADMINISTRATOR is not a superuser. That is the model
-- R__seed_role_permissions.sql documents and #1373 settled: SYSTEM_ADMINISTRATOR
-- holds the security/admin surface plus MCP administration, ADMIN is the
-- all-domain role. This migration makes the database agree with it. The grants
-- were never in version control, so there is nothing to revise in the seed --
-- the seed was right and the environment drifted from it.
--
-- SCOPE -- deliberately role-scoped, and only to the extra rows
-- The DELETE names SYSTEM_ADMINISTRATOR and removes only the grants the seed
-- does not make. It touches no other role. This is the opposite of the V25-V28
-- shape, whose role-agnostic DELETEs reached beyond the roles those migrations
-- were written for and silently stripped 48 grants off this very role -- the
-- footgun #1512 called out. A revoke that means one role says so in its WHERE
-- clause.
--
-- WHY A NAME LIST AND NOT A JOIN TO THE SEED
-- The seed is a separate repeatable file; SQL cannot read it. The list below is
-- a copy of its SYSTEM_ADMINISTRATOR grant block, and
-- RolePermissionBaselineTest#v31KeepListMatchesTheSeededSystemAdministratorGrants
-- fails the build if the two ever disagree. That test is the real guard: a name
-- misspelled here would drop a legitimate grant, and no SQL-side check can tell
-- a typo from a permission that simply is not registered yet.
--
-- ORDERING AND IDEMPOTENCE
-- Flyway runs versioned migrations before repeatable ones, so on a fresh
-- database this executes against an unseeded table and deletes nothing; the
-- baseline arrives afterwards. On an existing database the repeatable seed
-- re-runs immediately after this migration (its checksum changed in the same
-- change), which both restores the 40 canonical grants if anything here were
-- wrong and aborts loudly through its own section-4 assertion if a name failed
-- to resolve. Re-running this statement is a no-op: the second pass finds
-- nothing outside the keep list.
DO $$
DECLARE
    -- The SYSTEM_ADMINISTRATOR grant block of R__seed_role_permissions.sql, verbatim.
    baseline CONSTANT TEXT[] := ARRAY[
        'image:image:store',
        'mcp:chat:execute',
        'mcp:chat:stream',
        'mcp:document:ingest',
        'mcp:llm_api:create',
        'mcp:llm_api:delete',
        'mcp:llm_api:update',
        'mcp:llm_api:view',
        'mcp:system_prompt:create',
        'mcp:system_prompt:delete',
        'mcp:system_prompt:update',
        'mcp:system_prompt:view',
        'mcp:tool:manage',
        'mcp:tool:view',
        'nlti:audit:read',
        'nlti:request:read',
        'nlti:request:submit',
        'people:compliance:view',
        'security:audit:create',
        'security:audit:export',
        'security:audit:view',
        'security:authorization:decide',
        'security:permission:register',
        'security:permission:view',
        'security:role:assign',
        'security:role:create',
        'security:role:delete',
        'security:role:edit',
        'security:role:view',
        'security:token:issue_internal',
        'security:user:create',
        'security:user:delete',
        'security:user:edit',
        'security:user:view',
        'security:user_account_state:manage',
        'security:user_account_state:view',
        'supplier:audit:read',
        'supplier:transmission:read',
        'supplier:transmission:resolve',
        'workorder:events:replay'
    ];
    sa_role_id UUID;
    held INTEGER;
    unresolved TEXT[];
    revoked INTEGER;
BEGIN
    SELECT id INTO sa_role_id FROM roles WHERE name = 'SYSTEM_ADMINISTRATOR';
    IF sa_role_id IS NULL THEN
        RAISE NOTICE 'V31: the SYSTEM_ADMINISTRATOR role does not exist; nothing to revoke.';
        RETURN;
    END IF;

    SELECT COUNT(*) INTO held FROM role_permissions WHERE role_id = sa_role_id;
    IF held = 0 THEN
        -- Fresh database: the repeatable baseline has not run yet, so there is no
        -- drift to correct and no permissions table to resolve names against.
        RAISE NOTICE 'V31: SYSTEM_ADMINISTRATOR holds no grants yet; nothing to revoke.';
        RETURN;
    END IF;

    -- Reported, not fatal. An unregistered baseline name cannot cause a wrong
    -- revoke -- the role could not hold a permission that does not exist -- so
    -- aborting a deploy over one would trade a real outage for a theoretical
    -- defect that the parity test already covers. It is still worth naming: on an
    -- environment where the whole catalog is registered, an unresolved name means
    -- this list has drifted from the seed.
    -- Aliased rather than bare: an unqualified `name` inside the NOT EXISTS would
    -- resolve to permissions.name and make the predicate a tautology.
    SELECT array_agg(b.n ORDER BY b.n) INTO unresolved
    FROM unnest(baseline) AS b(n)
    WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.name = b.n);

    IF unresolved IS NOT NULL THEN
        RAISE WARNING 'V31: % baseline name(s) are not registered and were skipped: %',
            array_length(unresolved, 1), array_to_string(unresolved, ', ');
    END IF;

    DELETE FROM role_permissions
    WHERE role_id = sa_role_id
      AND permission_id NOT IN (SELECT id FROM permissions WHERE name = ANY(baseline));

    GET DIAGNOSTICS revoked = ROW_COUNT;
    RAISE NOTICE 'V31: revoked % grant(s) from SYSTEM_ADMINISTRATOR; % held before, % remain.',
        revoked, held, held - revoked;
END $$;
