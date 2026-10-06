-- CAP:550 S3 (#2504; SPEC-accounting-workspace §7.3, AW4, AW6): retire the alpha fixture role
-- ACCOUNTING_ASSOCIATE in favour of the floor and template role ACCOUNTING_CLERK, and revoke
-- accounting:ap:pay from the clerk (clerks never pay bills, §4.3).
--
-- Every tenant in ext_tenant is visited, each under its own transaction-local binding: every
-- scoped table is FORCE ROW LEVEL SECURITY, so the owner running Flyway sees only the bound
-- tenant's rows (the two-binding pattern of R__seed_tenant_template.sql). Every statement also
-- names the tenant explicitly, so the migration does the same thing where the migrating role
-- bypasses row-level security (a superuser, as in the Testcontainers ITs). Per tenant:
--
--   * ACCOUNTING_ASSOCIATE present, no ACCOUNTING_CLERK: rename it in place — same id, same
--     grants, same role_assignments — and set the clerk's description and persona (the precedent of
--     V8__rename_employee_deactivate_to_activation.sql: the row is keyed by id, so nothing that
--     references it has to move).
--   * Both present: move the associate's role_assignments to the clerk, skipping a user that already
--     holds the clerk, then delete the associate's grants and its row.
--   * Then delete the clerk's accounting:ap:pay grant. Grants are additive in the repeatable seeds
--     (R__seed_role_permissions.sql "Grants are additive"), so a revoke needs this versioned
--     migration; the seeds that run after it no longer grant it.
--
-- A tenant without the associate — every fresh database: versioned migrations run before the
-- repeatable seeds, and bulk-loaded roles arrive after startup — is left untouched. One NOTICE per
-- tenant says which case applied. Changes are stamped 'retire-accounting-associate'.
--
-- Nothing here creates ACCOUNTING_CLERK or grants to it: R__seed_reference_security.sql creates it
-- for alpha (ON CONFLICT, so a renamed row keeps its id), R__seed_role_permissions.sql grants it,
-- R__seed_tenant_template.sql copies it to the platform template, and existing tenants other than
-- alpha receive it through POST /v1/platform/tenants/{tenantId}/roles/reconcile-template.
DO $$
DECLARE
    tenant      record;
    associate   uuid;
    clerk       uuid;
    moved       integer;
    revoked     integer;
    actor       CONSTANT text := 'retire-accounting-associate';
BEGIN
    FOR tenant IN SELECT tenant_id, slug FROM ext_tenant ORDER BY tenant_id LOOP
        PERFORM set_config('app.current_tenant', tenant.tenant_id::text, true);

        SELECT id INTO associate FROM roles WHERE tenant_id = tenant.tenant_id AND name = 'ACCOUNTING_ASSOCIATE';
        SELECT id INTO clerk     FROM roles WHERE tenant_id = tenant.tenant_id AND name = 'ACCOUNTING_CLERK';

        IF associate IS NULL THEN
            RAISE NOTICE 'retire-accounting-associate: tenant % (%): ACCOUNTING_ASSOCIATE absent, nothing to do',
                tenant.slug, tenant.tenant_id;
            CONTINUE;
        END IF;

        IF clerk IS NULL THEN
            UPDATE roles
               SET name = 'ACCOUNTING_CLERK',
                   description = 'Accounting clerk: clears the accounting to-do list, matches customer payments, checks bills and prepares the bank check-up; never pays bills',
                   persona_title = 'accounting clerk',
                   persona_focus = 'ledger-facing context, reconciliation, and financial accuracy',
                   persona_tone = 'audit-aware, posting-precise, and careful with financial claims',
                   mcp_persona_rank = 50,
                   mcp_persona_eligible = true,
                   last_modified_at = now(),
                   last_modified_by = actor,
                   updated_at = now()
             WHERE tenant_id = tenant.tenant_id AND id = associate;
            clerk := associate;
            RAISE NOTICE 'retire-accounting-associate: tenant % (%): ACCOUNTING_ASSOCIATE renamed to ACCOUNTING_CLERK (id % kept)',
                tenant.slug, tenant.tenant_id, clerk;
        ELSE
            UPDATE role_assignments ra
               SET role_id = clerk,
                   last_modified_at = now(),
                   last_modified_by = actor,
                   updated_at = now()
             WHERE ra.tenant_id = tenant.tenant_id
               AND ra.role_id = associate
               AND NOT EXISTS (SELECT 1 FROM role_assignments held
                                WHERE held.tenant_id = ra.tenant_id
                                  AND held.user_id = ra.user_id
                                  AND held.role_id = clerk);
            GET DIAGNOSTICS moved = ROW_COUNT;
            DELETE FROM role_assignments WHERE tenant_id = tenant.tenant_id AND role_id = associate;
            DELETE FROM role_permissions WHERE tenant_id = tenant.tenant_id AND role_id = associate;
            DELETE FROM roles WHERE tenant_id = tenant.tenant_id AND id = associate;
            RAISE NOTICE 'retire-accounting-associate: tenant % (%): ACCOUNTING_ASSOCIATE merged into ACCOUNTING_CLERK (% assignment(s) moved, role % deleted)',
                tenant.slug, tenant.tenant_id, moved, associate;
        END IF;

        DELETE FROM role_permissions rp
         USING permissions p
         WHERE p.id = rp.permission_id
           AND p.name = 'accounting:ap:pay'
           AND rp.tenant_id = tenant.tenant_id
           AND rp.role_id = clerk;
        GET DIAGNOSTICS revoked = ROW_COUNT;
        RAISE NOTICE 'retire-accounting-associate: tenant % (%): accounting:ap:pay revoked from ACCOUNTING_CLERK (% row(s))',
            tenant.slug, tenant.tenant_id, revoked;
    END LOOP;
END $$;
