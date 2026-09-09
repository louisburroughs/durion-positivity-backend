# Baseline and tenancy tooling

The Flyway history of every persisting module was flattened on 2026-09-09 into one baseline per
module (`V1__baseline_<module>.sql`), with the ADR-0062 tenancy schema folded in. These scripts
produced those baselines and are kept for provenance and for re-running the same transform if a
module is ever regenerated. They are not part of the build.

| Script | Purpose |
| --- | --- |
| `flatten.py <module> <schema-dump.sql> <out-dir>` | Turns a `pg_dump --schema-only` of a migrated database into `V1__baseline_<module>.sql` plus `tenancy-global-tables.txt`, adding `tenant_id`, RLS, re-scoped unique constraints, `(tenant_id, id)` keys and composite foreign keys to every table not on the module's global whitelist. The whitelist itself is the `COMMON_GLOBAL` / `MODULE_GLOBAL` / `WHOLE_MODULE_GLOBAL` tables at the top of the script. |
| `rescope_conflicts.py <scoped-tables-file> <sql-file>` | Rewrites `ON CONFLICT (cols)` to `ON CONFLICT (tenant_id, cols)` for inserts into scoped tables (used on the repeatable seed scripts, whose unique constraints now lead with `tenant_id`). |
| `normalize.py <dump.sql> <out.sql> <dir-of-flatten.py>` | Strips the tenancy additions and pg_dump noise from a schema dump so the flattened schema can be diffed against the retired history's schema. The flatten was accepted with zero differences across all 25 modules. |

How the 2026-09-09 flatten was verified, per module: migrate the retired scripts into a scratch
database with Flyway 12.4.0, `pg_dump --schema-only`, run `flatten.py`, apply the result plus the
repeatable seeds to a fresh database, dump it again, normalise both dumps and diff (empty), and
compare seed row counts per table (identical). Data seeded by retired versioned scripts was carried
into `V2__seed_<module>.sql` from a `pg_dump --data-only --column-inserts` of the versioned-only
migration. See `docs/TENANCY_SCHEMA.md` for the conventions the baselines follow.
