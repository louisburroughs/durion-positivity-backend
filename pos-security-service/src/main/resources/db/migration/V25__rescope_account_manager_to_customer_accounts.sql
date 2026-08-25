-- #1499 / #1512: rescope ACCOUNT_MANAGER to a customer-accounts (AR) role and move
-- accounting-management authority to CONTROLLER, per
-- docs/rbac-permission-role-audit-2026-08.md §6 (decided 2026-08-25).
--
-- ACCOUNT_MANAGER keeps customer payments, credit memos, invoicing/billing and
-- (per decision f) reporting:view:financial-statements. Everything else it held --
-- chart of accounts, journal entries, GL/posting-category/mapping-key/default-mapping
-- configuration, posting rules, accounting events, exports and AP -- was accounting
-- management, not customer accounts, and moves to CONTROLLER (V24 creates that role;
-- R__seed_role_permissions.sql grants CONTROLLER its full TO-BE set, including these
-- moved codes, in the same change so re-seeding a fresh database matches).
--
-- The repeatable seed is additive (ON CONFLICT DO NOTHING) and never deletes, so
-- revoking ACCOUNT_MANAGER's moved grants needs this versioned migration -- the V23
-- precedent for a deliberate, forward-only revoke.
--
-- Also retires six dead codes (§3: enforced nowhere, superseded by their live
-- replacements) from every role that holds them:
--   accounting:ap:approve / accounting:ap:reject   -- superseded by accounting:ap:pay
--   accounting:mapping:view/create/edit/deactivate -- superseded by the
--     gl-mapping / mapping-key / default-mapping families
-- Deleting the grant rather than leaving it in place is deliberate: an enforced-nowhere
-- permission that still resolves onto a JWT is a stale capability an operator could
-- reasonably believe does something.

-- ---------------------------------------------------------------------------
-- 1. ACCOUNT_MANAGER loses the 32 accounting-management codes that moved to
--    CONTROLLER. reporting:view:financial-statements is NOT in this list --
--    ACCOUNT_MANAGER retains it per decision f.
-- ---------------------------------------------------------------------------
DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE name = 'ACCOUNT_MANAGER')
  AND permission_id IN (
      SELECT id FROM permissions WHERE name IN (
          'accounting:coa:view',
          'accounting:coa:create',
          'accounting:coa:edit',
          'accounting:coa:deactivate',
          'accounting:je:view',
          'accounting:je:create',
          'accounting:je:post',
          'accounting:je:reverse',
          'accounting:gl-mapping:create',
          'accounting:gl-mapping:resolve',
          'accounting:mapping-key:view',
          'accounting:mapping-key:create',
          'accounting:mapping-key:edit',
          'accounting:mapping-key:deactivate',
          'accounting:default-mapping:view',
          'accounting:default-mapping:create',
          'accounting:default-mapping:edit',
          'accounting:default-mapping:delete',
          'accounting:posting-category:view',
          'accounting:posting-category:create',
          'accounting:posting-category:edit',
          'accounting:posting-category:deactivate',
          'accounting:posting_rules:view',
          'accounting:posting_rules:create',
          'accounting:posting_rules:publish',
          'accounting:events:view',
          'accounting:events:submit',
          'accounting:events:retry',
          'accounting:events:reprocess',
          'accounting:export:view',
          'accounting:ap:view',
          'accounting:ap:pay'
      )
  );

-- ---------------------------------------------------------------------------
-- 2. Retire the six dead codes from every role that holds them (ADMIN,
--    ACCOUNT_MANAGER and ACCOUNTING_ASSOCIATE hold various of them). Not scoped
--    to a single role: these codes are enforced nowhere for anyone (§3), so no
--    role should keep them.
-- ---------------------------------------------------------------------------
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE name IN (
        'accounting:ap:approve',
        'accounting:ap:reject',
        'accounting:mapping:view',
        'accounting:mapping:create',
        'accounting:mapping:edit',
        'accounting:mapping:deactivate'
    )
);
